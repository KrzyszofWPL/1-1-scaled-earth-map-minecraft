package earth.tellus.geoid.world;

import earth.tellus.geoid.math.Geodetic;
import earth.tellus.geoid.math.GeoProjection;
import earth.tellus.geoid.math.SphereMath;
import earth.tellus.geoid.math.Vec3;

/**
 * Seamless coordinate folding and wrapping — the mechanism behind circumnavigation.
 *
 * <p><b>The core trick.</b> The projected planet is periodic in longitude: terrain at map-X {@code x}
 * is byte-for-byte identical to terrain at {@code x + P} where {@code P = projection.wrapPeriodX()},
 * because Tellus generation is a pure function of geodetic position. We exploit that periodicity with
 * a <em>floating origin</em>:
 *
 * <pre>
 *   trueMapX = minecraftX + windowOffsetX          // windowOffsetX is always an integer multiple of P
 * </pre>
 *
 * As the player walks east forever, {@code trueMapX} grows without bound while their Minecraft X is
 * kept inside a single period window {@code [-P/2, +P/2)}. Each time they cross the antimeridian seam
 * we (a) add {@code +-P} to {@code windowOffsetX} and (b) teleport their Minecraft X by {@code -+P}.
 * The two changes cancel in {@code trueMapX}, so the geodetic position is perfectly continuous, and
 * because the terrain one period away is identical, the teleport is invisible <em>provided</em> we
 * have pre-generated the overlap ahead of the seam (see {@link #seamBridgeTargets}).
 *
 * <p>The pole seam is handled separately by {@link #foldPole}: latitude cannot exceed +-90 degrees, so
 * "walking over the pole" is a longitude +180 flip plus a heading reversal.
 *
 * <p>This class is pure logic (no Minecraft imports) so it is unit-testable; the mixin/tick layer is
 * responsible for actually moving the player and issuing chunk tickets from the results here.
 */
public final class WorldFolding {

    /** Result of a fold check for one tick. */
    public static final class FoldResult {
        /** True if the player must be teleported this tick to stay inside the window. */
        public final boolean teleported;
        public final double newMinecraftX;
        public final double newMinecraftZ;
        /** Added to the player's heading (radians) — non-zero only on a pole crossing. */
        public final double bearingDelta;
        public final boolean crossedPole;

        FoldResult(boolean teleported, double newX, double newZ, double bearingDelta, boolean crossedPole) {
            this.teleported = teleported;
            this.newMinecraftX = newX;
            this.newMinecraftZ = newZ;
            this.bearingDelta = bearingDelta;
            this.crossedPole = crossedPole;
        }

        static FoldResult none(double x, double z) {
            return new FoldResult(false, x, z, 0.0, false);
        }
    }

    private final GeoProjection projection;
    private final double seaLevelY;
    private final int overlapChunks;

    /** Always a multiple of the wrap period. Accumulates as the player circumnavigates. */
    private double windowOffsetX;

    public WorldFolding(GeoProjection projection, double seaLevelY, int overlapChunks) {
        this.projection = projection;
        this.seaLevelY = seaLevelY;
        this.overlapChunks = overlapChunks;
    }

    public double windowOffsetX() {
        return windowOffsetX;
    }

    // ------------------------------------------------------------------ conversions

    /** Minecraft position -> geodetic, accounting for the current window offset. */
    public Geodetic mcToGeodetic(double mcX, double mcY, double mcZ) {
        double trueMapX = mcX + windowOffsetX;
        Geodetic surface = projection.fromMap(trueMapX, mcZ);
        return surface.withAltitude(mcY - seaLevelY);
    }

    /** Geodetic -> Minecraft position within the current window. */
    public Vec3 geodeticToMc(Geodetic g) {
        Vec3 map = projection.toMap(g.latRad, g.lonRad);
        double mcX = map.x - windowOffsetX;
        double mcZ = map.z;
        double mcY = seaLevelY + g.altitude;
        return new Vec3(mcX, mcY, mcZ);
    }

    // ------------------------------------------------------------------ folding

    /**
     * Checks whether the player needs a longitudinal fold this tick and, if so, computes the
     * teleport. Call every tick with the player's current Minecraft X/Z.
     *
     * <p>We fold when the player's Minecraft X leaves the safe window {@code [-P/2, +P/2)}. The teleport
     * distance is exactly one period, so terrain identity is preserved.
     */
    public FoldResult foldLongitude(double mcX, double mcZ) {
        double period = projection.wrapPeriodX();
        if (!Double.isFinite(period)) {
            return FoldResult.none(mcX, mcZ);
        }
        double half = period * 0.5;
        if (mcX >= half) {
            windowOffsetX += period;
            return new FoldResult(true, mcX - period, mcZ, 0.0, false);
        }
        if (mcX < -half) {
            windowOffsetX -= period;
            return new FoldResult(true, mcX + period, mcZ, 0.0, false);
        }
        return FoldResult.none(mcX, mcZ);
    }

    /**
     * Handles the pole seam. When the player's latitude would exceed the pole, they instead continue
     * onto the far side: latitude reflects back off the pole, longitude flips by 180 degrees, and their
     * heading reverses. In Minecraft-X terms the longitude flip is a half-period shift.
     *
     * @param mcZ the player's Minecraft Z <em>after</em> a step (may be outside the valid Z band)
     * @return a fold describing the reflected position, or {@code none} if no pole was crossed
     */
    public FoldResult foldPole(double mcX, double mcZ) {
        double zLimitNorth = -SphereMath.EARTH_RADIUS * (Math.PI / 2.0); // lat = +90 -> most negative Z
        double zLimitSouth = SphereMath.EARTH_RADIUS * (Math.PI / 2.0);  // lat = -90
        double period = projection.wrapPeriodX();
        double halfPeriod = period * 0.5;

        if (mcZ < zLimitNorth) {
            // Overshot the north pole: reflect Z back, flip longitude by half a period.
            double reflectedZ = 2.0 * zLimitNorth - mcZ;
            double flippedX = wrapIntoWindow(mcX + halfPeriod, period);
            return new FoldResult(true, flippedX, reflectedZ, Math.PI, true);
        }
        if (mcZ > zLimitSouth) {
            double reflectedZ = 2.0 * zLimitSouth - mcZ;
            double flippedX = wrapIntoWindow(mcX + halfPeriod, period);
            return new FoldResult(true, flippedX, reflectedZ, Math.PI, true);
        }
        return FoldResult.none(mcX, mcZ);
    }

    private static double wrapIntoWindow(double x, double period) {
        double half = period * 0.5;
        double w = ((x + half) % period + period) % period - half;
        return w;
    }

    // ------------------------------------------------------------------ seam pre-generation

    /**
     * Given the player's current Minecraft X, returns the map-X of the overlap strip that must be
     * pre-generated ahead of the seam so the fold teleport is invisible. Returns {@code NaN} when the
     * player is nowhere near a seam (the common case, so callers can cheaply skip).
     *
     * <p>The returned value is the Minecraft-X at which the far-side terrain should be mirrored in;
     * the chunk loader converts it to chunk coordinates and issues low-priority tickets.
     */
    public double seamBridgeTargets(double mcX) {
        double period = projection.wrapPeriodX();
        if (!Double.isFinite(period)) {
            return Double.NaN;
        }
        double half = period * 0.5;
        double bridge = overlapChunks * 16.0;
        if (mcX > half - bridge) {
            // Approaching +seam: mirror the terrain that lives just past -half.
            return -half + (mcX - half);
        }
        if (mcX < -half + bridge) {
            return half + (mcX + half);
        }
        return Double.NaN;
    }

    public GeoProjection projection() {
        return projection;
    }
}
