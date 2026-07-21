package earth.tellus.geoid.client;

import earth.tellus.geoid.net.GeoStatePayload;
import earth.tellus.geoid.physics.GeoidGravity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

/**
 * Client entry point.
 *
 * <p>Receives {@link GeoStatePayload} snapshots into {@link ClientGeoState} and teaches
 * {@link GeoidGravity} how to find the local player's synced state, so the client runs matching gravity
 * prediction during core traversals (no rubber-banding) and the camera mixin can roll the horizon.
 */
@Environment(EnvType.CLIENT)
public final class GeoidClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Apply incoming state snapshots on the client thread.
        ClientPlayNetworking.registerGlobalReceiver(GeoStatePayload.ID, (payload, context) ->
                context.client().execute(() -> ClientGeoState.accept(payload)));

        // Let the shared gravity hook resolve the local player's state on the client side.
        GeoidGravity.setClientLookup(entity -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null && entity == mc.player) {
                return ClientGeoState.local();
            }
            return null; // other players are driven by the server; don't predict them locally
        });
    }
}
