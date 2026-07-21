package earth.tellus.geoid.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Correctness tests for the pure spherical math. These need no Minecraft and pin down the invariants
 * the whole engine relies on: projection/ECEF round-trips, antipode symmetry, interior gravity, and the
 * 180-degree gravity flip across the core.
 */
class SphereMathTest {

    private static final double EPS = 1.0e-6;

    @Test
    void geodeticEcefRoundTrip() {
        Geodetic warsaw = Geodetic.ofDegrees(52.2297, 21.0122, 137.0);
        Vec3 ecef = Ecef.geodeticToEcef(warsaw);
        Geodetic back = Ecef.ecefToGeodetic(ecef);
        assertEquals(warsaw.latRad, back.latRad, EPS);
        assertEquals(warsaw.lonRad, back.lonRad, EPS);
        assertEquals(warsaw.altitude, back.altitude, 1.0e-3);
    }

    @Test
    void equirectangularRoundTrip() {
        GeoProjection p = EquirectangularProjection.INSTANCE;
        Geodetic g = Geodetic.ofDegrees(-33.8688, 151.2093, 0); // Sydney
        Vec3 map = p.toMap(g.latRad, g.lonRad);
        Geodetic back = p.fromMap(map.x, map.z);
        assertEquals(g.latRad, back.latRad, EPS);
        assertEquals(g.lonRad, back.lonRad, EPS);
    }

    @Test
    void antipodeOfWarsawIsNearNewZealandOcean() {
        // Warsaw's antipode sits in the South Pacific east of New Zealand.
        Geodetic warsaw = Geodetic.ofDegrees(52.2297, 21.0122, 0);
        Geodetic anti = warsaw.antipode();
        assertEquals(-52.2297, anti.latDeg(), 1.0e-4);
        // Longitude flips by 180 and wraps into [-180,180): 21.0122 -> -158.9878
        assertEquals(-158.9878, anti.lonDeg(), 1.0e-4);
        // Antipode of antipode is the original point.
        Geodetic there = anti.antipode();
        assertEquals(warsaw.latRad, there.latRad, EPS);
        assertEquals(warsaw.lonRad, there.lonRad, EPS);
    }

    @Test
    void gravityIsZeroAtCentreAndSurfaceStrengthAtSurface() {
        assertEquals(0.0, SphereMath.gravityMagnitude(0.0), EPS);
        assertEquals(SphereMath.SURFACE_GRAVITY, SphereMath.gravityMagnitude(SphereMath.EARTH_RADIUS), 1.0e-9);
        // Halfway to the centre, uniform-sphere gravity is half of surface.
        assertEquals(SphereMath.SURFACE_GRAVITY * 0.5,
                SphereMath.gravityMagnitude(SphereMath.EARTH_RADIUS * 0.5), 1.0e-9);
    }

    @Test
    void coreTraversalFlipsGravityByOneHundredEightyDegrees() {
        CoreTunnelHarness.assertFlip();
    }

    @Test
    void seamTeleportPreservesGeodeticLongitude() {
        // Crossing the antimeridian by one wrap period must not change the true longitude.
        double period = EquirectangularProjection.INSTANCE.wrapPeriodX();
        Geodetic before = EquirectangularProjection.INSTANCE.fromMap(period / 2.0 - 1.0, 0);
        Geodetic afterWrap = EquirectangularProjection.INSTANCE.fromMap(period / 2.0 - 1.0 - period, 0);
        // After subtracting a full period, canonicalised longitude is identical.
        assertEquals(before.lonRad, afterWrap.lonRad, EPS);
    }

    @Test
    void greatCircleDestinationCirclesBackToStart() {
        // Walking exactly one circumference along any bearing returns to the start point.
        Geodetic start = Geodetic.ofDegrees(0, 0, 0);
        Geodetic end = SphereMath.destination(start, Math.toRadians(90), SphereMath.CIRCUMFERENCE);
        assertTrue(SphereMath.haversine(start, end) < 5.0,
                "expected to return within 5 blocks of start, got " + SphereMath.haversine(start, end));
    }

    /** Kept separate to exercise {@link CoreTunnel} without importing the world package everywhere. */
    static final class CoreTunnelHarness {
        static void assertFlip() {
            earth.tellus.geoid.world.CoreTunnel t =
                    new earth.tellus.geoid.world.CoreTunnel(Geodetic.ofDegrees(52.2297, 21.0122, 0));
            double gNear = t.gravityY(1000.0);                       // just below near surface
            double gFar = t.gravityY(SphereMath.DIAMETER - 1000.0);  // just below far surface
            assertTrue(gNear < 0, "near-side gravity should pull -Y");
            assertTrue(gFar > 0, "far-side gravity should pull +Y (flipped)");
            assertEquals(Math.abs(gNear), Math.abs(gFar), 1.0e-9, "symmetric magnitude across the core");
            // Frame flip: identity near the surface, ~180 deg past the centre.
            assertEquals(0.0, angleOf(t.frameAt(0.0)), 1.0e-6);
            assertEquals(Math.PI, angleOf(t.frameAt(SphereMath.DIAMETER)), 1.0e-3);
        }

        private static double angleOf(Quat q) {
            return 2.0 * Math.acos(Math.min(1.0, Math.abs(q.normalize().w)));
        }
    }
}
