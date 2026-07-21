package earth.tellus.geoid.integration;

import earth.tellus.geoid.math.EquirectangularProjection;
import earth.tellus.geoid.math.GeoProjection;
import earth.tellus.geoid.math.Geodetic;

/**
 * The no-Tellus fallback: a plain equirectangular planet with a flat sea-level surface.
 *
 * <p>Lets the entire spherical engine — circumnavigation, folding, gravity, core traversal — run and be
 * tested on an ordinary Fabric world with no terrain dependency. Everything is identical to the real
 * integration except the ground is flat, so any behaviour verified here transfers directly once Tellus
 * is supplying real heights.
 */
public final class FallbackTellusBridge implements TellusBridge {

    private final double seaLevelY;

    public FallbackTellusBridge(double seaLevelY) {
        this.seaLevelY = seaLevelY;
    }

    @Override
    public GeoProjection projection() {
        return EquirectangularProjection.INSTANCE;
    }

    @Override
    public double surfaceAltitude(Geodetic g) {
        return 0.0; // flat reference sphere
    }

    @Override
    public boolean isTellusPresent() {
        return false;
    }

    @Override
    public double seaLevelY() {
        return seaLevelY;
    }
}
