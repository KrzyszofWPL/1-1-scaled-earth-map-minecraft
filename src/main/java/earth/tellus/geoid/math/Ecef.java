package earth.tellus.geoid.math;

/**
 * Earth-Centred, Earth-Fixed (ECEF) Cartesian conversions and the local tangent-plane (ENU) basis.
 *
 * <p>ECEF is the true 3-D representation of the planet. It is what makes "the world is a sphere"
 * actually mean something: two points that are far apart on the flat projected map but adjacent
 * across the antimeridian are <em>also</em> adjacent in ECEF, and the antipode reached by digging
 * through the core is a genuine straight line in ECEF. All folding/wrapping/gravity math is derived
 * from here.
 *
 * <p>Spherical model (not the WGS84 ellipsoid) is used by default because Tellus maps blocks 1:1 to a
 * sphere; swapping in ellipsoidal flattening later only touches {@link #geodeticToEcef} /
 * {@link #ecefToGeodetic}.
 *
 * <p>ECEF axes: +X through (lat 0, lon 0), +Y through (lat 0, lon 90E), +Z through the north pole.
 */
public final class Ecef {

    private Ecef() {
    }

    /** Geodetic -> ECEF position on the reference sphere of radius {@link SphereMath#EARTH_RADIUS}. */
    public static Vec3 geodeticToEcef(Geodetic g) {
        double r = SphereMath.EARTH_RADIUS + g.altitude;
        double cosLat = Math.cos(g.latRad);
        double sinLat = Math.sin(g.latRad);
        double cosLon = Math.cos(g.lonRad);
        double sinLon = Math.sin(g.lonRad);
        return new Vec3(
                r * cosLat * cosLon,
                r * cosLat * sinLon,
                r * sinLat);
    }

    /** ECEF position -> geodetic. Inverse of {@link #geodeticToEcef} for the spherical model. */
    public static Geodetic ecefToGeodetic(Vec3 p) {
        double r = p.length();
        if (r < 1.0e-6) {
            // At the exact centre latitude/longitude are undefined; return a stable sentinel.
            return new Geodetic(0, 0, -SphereMath.EARTH_RADIUS);
        }
        double lat = Math.asin(clamp(p.z / r, -1.0, 1.0));
        double lon = Math.atan2(p.y, p.x);
        return new Geodetic(lat, lon, r - SphereMath.EARTH_RADIUS);
    }

    /** Local "up" (radial, outward) unit vector in ECEF at the given geodetic point. */
    public static Vec3 up(Geodetic g) {
        double cosLat = Math.cos(g.latRad);
        double sinLat = Math.sin(g.latRad);
        double cosLon = Math.cos(g.lonRad);
        double sinLon = Math.sin(g.lonRad);
        return new Vec3(cosLat * cosLon, cosLat * sinLon, sinLat);
    }

    /** Local "east" unit vector in ECEF. */
    public static Vec3 east(Geodetic g) {
        double cosLon = Math.cos(g.lonRad);
        double sinLon = Math.sin(g.lonRad);
        return new Vec3(-sinLon, cosLon, 0.0);
    }

    /** Local "north" unit vector in ECEF. */
    public static Vec3 north(Geodetic g) {
        double cosLat = Math.cos(g.latRad);
        double sinLat = Math.sin(g.latRad);
        double cosLon = Math.cos(g.lonRad);
        double sinLon = Math.sin(g.lonRad);
        return new Vec3(-sinLat * cosLon, -sinLat * sinLon, cosLat);
    }

    /**
     * Rotates an ECEF vector into the local East/North/Up frame anchored at {@code g}.
     * Returns {@code (east-component, north-component, up-component)}.
     */
    public static Vec3 ecefToEnu(Vec3 ecefVector, Geodetic g) {
        Vec3 e = east(g);
        Vec3 n = north(g);
        Vec3 u = up(g);
        return new Vec3(ecefVector.dot(e), ecefVector.dot(n), ecefVector.dot(u));
    }

    /** Rotates a local ENU vector back into ECEF. Inverse of {@link #ecefToEnu}. */
    public static Vec3 enuToEcef(Vec3 enu, Geodetic g) {
        Vec3 e = east(g);
        Vec3 n = north(g);
        Vec3 u = up(g);
        return e.scale(enu.x).add(n.scale(enu.y)).add(u.scale(enu.z));
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
