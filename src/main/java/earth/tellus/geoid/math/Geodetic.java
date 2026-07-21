package earth.tellus.geoid.math;

/**
 * A geodetic coordinate on the modelled planet: latitude, longitude and altitude.
 *
 * <p>This is the engine's "source of truth". Every Minecraft-space coordinate is a <em>derived</em>
 * projection of a {@code Geodetic}; the reverse mapping is what lets us fold the world, wrap chunks
 * and teleport through the core while keeping perfect continuity.
 *
 * <ul>
 *   <li>{@link #latRad}: latitude in radians, {@code [-PI/2, +PI/2]} (north positive).</li>
 *   <li>{@link #lonRad}: longitude in radians, canonicalised to {@code [-PI, +PI)} (east positive).</li>
 *   <li>{@link #altitude}: metres/blocks above the reference sphere surface (may be negative when
 *       underground or during a core traversal, where it can reach {@code -2R}).</li>
 * </ul>
 */
public final class Geodetic {

    public final double latRad;
    public final double lonRad;
    public final double altitude;

    public Geodetic(double latRad, double lonRad, double altitude) {
        this.latRad = clampLat(latRad);
        this.lonRad = wrapLon(lonRad);
        this.altitude = altitude;
    }

    public static Geodetic ofDegrees(double latDeg, double lonDeg, double altitude) {
        return new Geodetic(Math.toRadians(latDeg), Math.toRadians(lonDeg), altitude);
    }

    public double latDeg() {
        return Math.toDegrees(latRad);
    }

    public double lonDeg() {
        return Math.toDegrees(lonRad);
    }

    public Geodetic withAltitude(double newAltitude) {
        return new Geodetic(latRad, lonRad, newAltitude);
    }

    /**
     * The antipode: the point diametrically opposite on the sphere.
     * Latitude negates, longitude flips by 180 degrees. Altitude is preserved (surface-to-surface).
     */
    public Geodetic antipode() {
        return new Geodetic(-latRad, lonRad + Math.PI, altitude);
    }

    /** Canonicalises longitude into {@code [-PI, +PI)}. This is the seam-fold used for wrapping. */
    public static double wrapLon(double lon) {
        double twoPi = 2.0 * Math.PI;
        double w = ((lon + Math.PI) % twoPi + twoPi) % twoPi - Math.PI;
        // % can yield exactly +PI due to rounding; force into half-open interval.
        return w >= Math.PI ? w - twoPi : w;
    }

    /** Latitudes are hard-clamped: the poles are singular and cannot be "passed" in lat space. */
    public static double clampLat(double lat) {
        double lim = Math.PI / 2.0;
        return lat < -lim ? -lim : (lat > lim ? lim : lat);
    }

    @Override
    public String toString() {
        return String.format("Geodetic(lat=%.6f deg, lon=%.6f deg, alt=%.2f)", latDeg(), lonDeg(), altitude);
    }
}
