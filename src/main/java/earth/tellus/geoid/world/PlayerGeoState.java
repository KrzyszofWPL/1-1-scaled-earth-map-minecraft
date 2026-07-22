package earth.tellus.geoid.world;

import earth.tellus.geoid.math.Ecef;
import earth.tellus.geoid.math.Geodetic;
import earth.tellus.geoid.math.Quat;
import earth.tellus.geoid.math.SphereMath;
import earth.tellus.geoid.math.Vec3;

/**
 * The authoritative spherical state of one player, kept in parallel with Minecraft's own position.
 *
 * <p>Minecraft still thinks the player is at some flat {@code (x, y, z)}. This object holds the truth
 * the engine reasons about: where the player is on the globe, which way is "down" for them right now,
 * and whether they are mid-traversal through the core. Every tick the engine reconciles the two:
 * flat position -> geodetic (via the projection) for normal play, and geodetic -> flat position when
 * a fold/wrap/core-teleport has to move the player.
 *
 * <p>Instances live on both sides: the server owns the canonical copy and streams it to the client
 * (see {@code GeoStatePayload}) so the client physics/camera stay in lock-step.
 */
public final class PlayerGeoState {

    /** Where the physics currently places the player, in traversal terms. */
    public enum Phase {
        /** Normal walking on the projected surface; gravity is plain -Y. */
        SURFACE,
        /** Digging down through the near hemisphere toward the centre. */
        CORE_DESCENT,
        /** Past the centre, "climbing" toward the antipodal surface. */
        CORE_ASCENT
    }

    /** Canonical geodetic position (source of truth). */
    public Geodetic geodetic;

    /** Player heading as a great-circle bearing (radians, clockwise from north). Driven by yaw. */
    public double bearingRad;

    /** Current traversal phase. */
    public Phase phase = Phase.SURFACE;

    /**
     * Signed distance travelled along the core diameter, {@code [0, 2R]}. 0 = near surface,
     * R = centre, 2R = antipodal surface. Only meaningful during CORE_* phases.
     */
    public double coreParam;

    /**
     * The rotation that takes world-space "up" (+Y) to the player's current local "up". Identity on
     * the surface; slerps to a 180-degree flip across a core traversal. The camera/renderer reads
     * this to tilt the horizon; the physics reads it to orient gravity.
     */
    public Quat frame = Quat.IDENTITY;

    /** Bumps every time the server rebases/folds this player, so the client can detect teleports. */
    public long foldEpoch;

    /**
     * The player's Minecraft X at the end of the previous tick. Used only to detect an Immersive
     * Portals wrap-portal crossing (see {@link WorldFolding#reconcileExternalFold}) by spotting a
     * one-period jump between ticks; {@link Double#NaN} until the first tick has run once.
     */
    public double lastMcX = Double.NaN;

    public PlayerGeoState(Geodetic geodetic) {
        this.geodetic = geodetic;
    }

    /** The player's ECEF position implied by the current geodetic. */
    public Vec3 ecef() {
        return Ecef.geodeticToEcef(geodetic);
    }

    /** True while the player is anywhere inside the planet on a core traversal. */
    public boolean inCore() {
        return phase != Phase.SURFACE;
    }

    /**
     * Traversal progress {@code [0,1]} used to drive the gravity slerp and camera tilt.
     * 0 at the near surface, 0.5 at the centre (weightless), 1 at the antipodal surface.
     */
    public double coreProgress() {
        return SphereMath.clamp(coreParam / SphereMath.DIAMETER, 0.0, 1.0);
    }

    @Override
    public String toString() {
        return "PlayerGeoState{" + geodetic + ", phase=" + phase
                + ", coreParam=" + String.format("%.1f", coreParam)
                + ", bearing=" + String.format("%.1f deg", Math.toDegrees(bearingRad)) + '}';
    }
}
