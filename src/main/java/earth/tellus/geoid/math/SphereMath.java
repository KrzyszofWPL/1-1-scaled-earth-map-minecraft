package earth.tellus.geoid.math;

/**
 * Planetary constants and great-circle utilities.
 *
 * <p>All distances are in Minecraft blocks, and the engine treats 1 block = 1 metre (the Tellus 1:1
 * contract). That makes the numbers physically meaningful and lets us reuse real geodesy formulae
 * unchanged.
 */
public final class SphereMath {

    /** Mean Earth radius in blocks (== metres at 1:1). */
    public static final double EARTH_RADIUS = 6_371_000.0;

    /** Great-circle circumference: the east-west and pole-to-pole-to-pole wrap period. */
    public static final double CIRCUMFERENCE = 2.0 * Math.PI * EARTH_RADIUS; // ~40,030,174 blocks

    /** Surface gravity magnitude used as the reference at r = R (blocks/tick^2 is applied elsewhere). */
    public static final double SURFACE_GRAVITY = 9.80665;

    /** Diameter — the full length of a straight core traversal from surface to antipodal surface. */
    public static final double DIAMETER = 2.0 * EARTH_RADIUS;

    private SphereMath() {
    }

    /**
     * Great-circle (haversine) distance between two surface points, in blocks.
     * Altitude is ignored (surface distance).
     */
    public static double haversine(Geodetic a, Geodetic b) {
        double dLat = b.latRad - a.latRad;
        double dLon = Geodetic.wrapLon(b.lonRad - a.lonRad);
        double sinLat = Math.sin(dLat * 0.5);
        double sinLon = Math.sin(dLon * 0.5);
        double h = sinLat * sinLat + Math.cos(a.latRad) * Math.cos(b.latRad) * sinLon * sinLon;
        return 2.0 * EARTH_RADIUS * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /** Initial bearing (radians, clockwise from north) of the great circle from {@code a} to {@code b}. */
    public static double initialBearing(Geodetic a, Geodetic b) {
        double dLon = Geodetic.wrapLon(b.lonRad - a.lonRad);
        double y = Math.sin(dLon) * Math.cos(b.latRad);
        double x = Math.cos(a.latRad) * Math.sin(b.latRad)
                - Math.sin(a.latRad) * Math.cos(b.latRad) * Math.cos(dLon);
        return Math.atan2(y, x);
    }

    /**
     * Destination point when travelling {@code distance} blocks from {@code start} along a constant
     * {@code bearing} (radians, clockwise from north). This is the exact great-circle step used to
     * advance a player who "keeps walking straight" during circumnavigation.
     */
    public static Geodetic destination(Geodetic start, double bearing, double distance) {
        double ang = distance / EARTH_RADIUS;
        double lat1 = start.latRad;
        double sinLat2 = Math.sin(lat1) * Math.cos(ang)
                + Math.cos(lat1) * Math.sin(ang) * Math.cos(bearing);
        double lat2 = Math.asin(clamp(sinLat2, -1.0, 1.0));
        double y = Math.sin(bearing) * Math.sin(ang) * Math.cos(lat1);
        double x = Math.cos(ang) - Math.sin(lat1) * sinLat2;
        double lon2 = start.lonRad + Math.atan2(y, x);
        return new Geodetic(lat2, lon2, start.altitude);
    }

    /**
     * Gravity magnitude at radius {@code r} for a uniform-density sphere:
     * <ul>
     *   <li>outside/at surface ({@code r >= R}): {@code g0 * (R/r)^2} (inverse-square).</li>
     *   <li>inside ({@code r < R}): {@code g0 * (r/R)} (linear, -> 0 at the centre).</li>
     * </ul>
     * The interior branch is what makes the core traversal feel right: gravity fades to nothing at
     * the centre, then grows again with the sign flipped on the far side.
     */
    public static double gravityMagnitude(double r) {
        double absR = Math.abs(r);
        if (absR >= EARTH_RADIUS) {
            double ratio = EARTH_RADIUS / absR;
            return SURFACE_GRAVITY * ratio * ratio;
        }
        return SURFACE_GRAVITY * (absR / EARTH_RADIUS);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Smoothstep easing on {@code [edge0, edge1]}, clamped, used for seamless transition blends. */
    public static double smoothstep(double edge0, double edge1, double x) {
        double t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }
}
