package earth.tellus.geoid.math;

/**
 * Plate-carree (equirectangular) projection: the reference/fallback chart.
 *
 * <p>Mapping (1 block = 1 metre):
 * <pre>
 *   mapX =  R * lon            (east positive; +X = East)
 *   mapZ = -R * lat            (north negative Z; matches vanilla "north = -Z")
 * </pre>
 * Both axes fit inside Minecraft's +-30M coordinate limit:
 * <ul>
 *   <li>mapX in [-pi*R, +pi*R] ~= +-20,015,087</li>
 *   <li>mapZ in [-pi*R/2, +pi*R/2] ~= +-10,007,543</li>
 * </ul>
 * so the entire planet is representable in a single Minecraft dimension. The only thing missing is
 * that the antimeridian and the poles are hard edges — which is precisely what {@code WorldFolding}
 * turns into seamless wraps.
 *
 * <p>The projection is deliberately <em>periodic</em> in longitude: {@code toMap(lat, lon)} and
 * {@code toMap(lat, lon + 2pi)} differ by exactly {@link #wrapPeriodX()} in X. Because Tellus terrain
 * generation is a pure function of geodetic position, terrain one wrap-period away is byte-identical,
 * which is what allows a wrap teleport of exactly one period to be invisible.
 */
public final class EquirectangularProjection implements GeoProjection {

    public static final EquirectangularProjection INSTANCE = new EquirectangularProjection();

    @Override
    public Vec3 toMap(double latRad, double lonRad) {
        double mapX = SphereMath.EARTH_RADIUS * lonRad;
        double mapZ = -SphereMath.EARTH_RADIUS * latRad;
        return new Vec3(mapX, 0.0, mapZ);
    }

    @Override
    public Geodetic fromMap(double mapX, double mapZ) {
        double lon = mapX / SphereMath.EARTH_RADIUS;
        double lat = -mapZ / SphereMath.EARTH_RADIUS;
        return new Geodetic(lat, lon, 0.0);
    }

    @Override
    public double wrapPeriodX() {
        return SphereMath.CIRCUMFERENCE; // 2*pi*R
    }

    @Override
    public String id() {
        return "equirectangular";
    }
}
