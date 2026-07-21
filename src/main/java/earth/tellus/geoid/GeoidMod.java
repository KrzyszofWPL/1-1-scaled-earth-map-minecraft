package earth.tellus.geoid;

import earth.tellus.geoid.net.GeoStatePayload;
import earth.tellus.geoid.world.GeoidServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entry point.
 *
 * <p>Wires the spherical engine into the server tick loop and registers the state-sync payload. The
 * heavy lifting lives in {@link GeoidServer}; this class just schedules it and cleans up per-player
 * state on disconnect.
 */
public final class GeoidMod implements ModInitializer {

    public static final String MOD_ID = "geoid";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Register the server -> client state snapshot channel.
        PayloadTypeRegistry.playS2C().register(GeoStatePayload.ID, GeoStatePayload.CODEC);

        // Drive the spherical engine once per player per tick.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var player : server.getPlayerManager().getPlayerList()) {
                try {
                    GeoidServer.get().tickPlayer(player);
                } catch (Throwable t) {
                    LOG.error("Geoid tick failed for {}", player.getGameProfile().getName(), t);
                }
            }
        });

        // Drop per-player spherical state when they leave.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                GeoidServer.get().forget(handler.getPlayer().getUuid()));

        LOG.info("Geoid spherical-earth engine initialised (companion to Tellus).");
    }
}
