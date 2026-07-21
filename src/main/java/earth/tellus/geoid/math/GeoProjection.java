package earth.tellus.geoid.math;

/**
 * Maps between geodetic coordinates and the flat Minecraft world grid.
 *
 * <p>Minecraft's voxel grid is inescapably flat and axis-aligned — we cannot literally curve it.
 * A {@code GeoProjection} is the chart that flattens the sphere onto that grid. The engine treats it
 * as an <em>atlas</em>: the whole planet is one map, and the discontinuities of the projection (the
 * antimeridian seam, the poles) are exactly the places where {@code WorldFolding} performs its
 * seamless wrap.
 *
 * <p>Tellus already picks a projection to lay down its terrain; {@code TellusBridge} adapts whatever
 * Tellus uses into this interface so the two mods agree on where every block lives. The built-in
 * {@link EquirectangularProjection} is the fallback and the reference implementation.
 */
public interface GeoProjection {

    /**
     * Projects geodetic (lat, lon) to flat map coordinates.
     * Returns {@code (mapX, mapZ)} packed in a {@link Vec3} ({@code y} unused / 0). Altitude maps to
     * Minecraft Y separately (surface altitude + sea-level offset), handled by the frame layer.
     */
    Vec3 toMap(double latRad, double lonRad);

    /** Inverse projection: flat map {@code (mapX, mapZ)} back to geodetic (lat, lon) in radians. */
    Geodetic fromMap(double mapX, double mapZ);

    /**
     * The signed map-X extent of one full longitudinal wrap (east-west circumference on the map).
     * Crossing this distance in +X must land on identical terrain — the basis of seamless wrap.
     * Return {@link Double#POSITIVE_INFINITY} if the projection does not wrap in X.
     */
    double wrapPeriodX();

    /** Human-readable id, used in logs and the Tellus handshake. */
    String id();
}
