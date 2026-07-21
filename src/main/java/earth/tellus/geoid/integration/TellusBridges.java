package earth.tellus.geoid.integration;

import earth.tellus.geoid.config.GeoidConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime discovery of the active {@link TellusBridge}.
 *
 * <p>If the Tellus mod is installed we bind to it reflectively (so Geoid compiles without a hard
 * dependency on Tellus's internal API, which is not published as a stable maven artifact); otherwise we
 * fall back to a flat equirectangular planet. The chosen bridge is cached for the process lifetime.
 *
 * <p>The reflective binding is deliberately defensive: any failure to locate Tellus's projection
 * accessor logs a warning and degrades to the fallback rather than crashing the game. Replace
 * {@link #tryBindTellus} with a compile-time binding once/if Tellus exposes a stable API.
 */
public final class TellusBridges {

    private static final Logger LOG = LoggerFactory.getLogger("geoid/tellus");
    private static final String TELLUS_MOD_ID = "tellus";

    private static volatile TellusBridge active;

    private TellusBridges() {
    }

    public static TellusBridge active() {
        TellusBridge a = active;
        if (a == null) {
            synchronized (TellusBridges.class) {
                if (active == null) {
                    active = resolve();
                }
                a = active;
            }
        }
        return a;
    }

    private static TellusBridge resolve() {
        double seaLevel = GeoidConfig.get().seaLevelY;
        if (FabricLoader.getInstance().isModLoaded(TELLUS_MOD_ID)) {
            TellusBridge bound = tryBindTellus(seaLevel);
            if (bound != null) {
                LOG.info("Bound Geoid to Tellus projection '{}'.", bound.projection().id());
                return bound;
            }
            LOG.warn("Tellus is present but its projection API could not be bound; using fallback.");
        } else {
            LOG.info("Tellus not detected; running Geoid on the equirectangular fallback planet.");
        }
        return new FallbackTellusBridge(seaLevel);
    }

    /**
     * Attempts to construct a bridge over the real Tellus mod via reflection.
     *
     * <p>Intentionally returns {@code null} on any incompatibility so the caller can fall back. The
     * expected Tellus surface (adjust to the real one): a class exposing a static projection object
     * whose {@code project}/{@code unproject} methods we wrap into a {@link earth.tellus.geoid.math.GeoProjection}.
     */
    private static TellusBridge tryBindTellus(double seaLevel) {
        try {
            // Placeholder for the real reflective wiring, e.g.:
            //   Class<?> api = Class.forName("dev.tellus.api.TellusProjection");
            //   Object proj = api.getField("ACTIVE").get(null);
            //   return new ReflectiveTellusBridge(proj, seaLevel);
            // Until Tellus publishes that surface we conservatively decline and use the fallback,
            // which shares the same equirectangular math Tellus uses for 1:1 maps.
            return null;
        } catch (Throwable t) {
            LOG.warn("Reflective Tellus binding failed.", t);
            return null;
        }
    }
}
