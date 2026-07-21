package earth.tellus.geoid.client;

import earth.tellus.geoid.math.Geodetic;
import earth.tellus.geoid.math.Quat;
import earth.tellus.geoid.net.GeoStatePayload;
import earth.tellus.geoid.world.PlayerGeoState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-side mirror of the local player's spherical state.
 *
 * <p>Fed by {@link GeoStatePayload} from the server. The client keeps this so it can (a) run matching
 * gravity prediction for the local player during a core traversal — avoiding rubber-banding — and
 * (b) roll the camera to match the frame. We also retain the previous frame so the renderer can slerp
 * between ticks for a perfectly smooth horizon roll.
 */
@Environment(EnvType.CLIENT)
public final class ClientGeoState {

    private static final PlayerGeoState LOCAL = new PlayerGeoState(new Geodetic(0, 0, 0));
    private static Quat previousFrame = Quat.IDENTITY;
    private static long lastFoldEpoch;

    private ClientGeoState() {
    }

    public static PlayerGeoState local() {
        return LOCAL;
    }

    public static Quat previousFrame() {
        return previousFrame;
    }

    /** Called on the client network thread when a snapshot arrives. */
    public static void accept(GeoStatePayload payload) {
        previousFrame = LOCAL.frame;
        payload.applyTo(LOCAL);
        if (payload.foldEpoch() != lastFoldEpoch) {
            lastFoldEpoch = payload.foldEpoch();
            onFold();
        }
    }

    /** A fold/teleport happened: reset interpolation so the client doesn't lerp across the seam jump. */
    private static void onFold() {
        previousFrame = LOCAL.frame;
    }
}
