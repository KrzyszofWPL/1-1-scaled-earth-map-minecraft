package earth.tellus.geoid.physics;

import java.util.function.Function;

import earth.tellus.geoid.world.CoreTunnel;
import earth.tellus.geoid.world.GeoidServer;
import earth.tellus.geoid.world.PlayerGeoState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * The single entry point the gravity mixin calls. Keeps the mixin trivial and, crucially, keeps all
 * client-only lookups behind an indirection so this class never forces a client class to load on a
 * dedicated server.
 *
 * <p>Contract: return {@code true} iff we took over gravity for this entity this tick (the mixin then
 * cancels vanilla gravity). We only ever take over for a player who is mid core-traversal; on the
 * surface we return {@code false} and vanilla gravity runs untouched, so ordinary play is byte-for-byte
 * unchanged.
 */
public final class GeoidGravity {

    /** Set by the client initializer to look up the local player's synced state. Null on servers. */
    private static volatile Function<Entity, PlayerGeoState> clientLookup;

    private GeoidGravity() {
    }

    public static void setClientLookup(Function<Entity, PlayerGeoState> lookup) {
        clientLookup = lookup;
    }

    /**
     * Applies spherical gravity to {@code self} for one tick if applicable.
     *
     * @return true if custom gravity was applied (vanilla should be cancelled), false to defer to vanilla
     */
    public static boolean apply(Entity self) {
        if (!(self instanceof PlayerEntity)) {
            return false;
        }
        PlayerGeoState state = lookupState(self);
        if (state == null || state.phase == PlayerGeoState.Phase.SURFACE) {
            return false;
        }
        double gy = CoreTunnel.gravityYForParam(state.coreParam);
        Vec3d v = self.getVelocity();
        self.setVelocity(v.x, (v.y + gy) * SphericalPhysics.AIR_DRAG, v.z);
        return true;
    }

    private static PlayerGeoState lookupState(Entity self) {
        if (self.getEntityWorld().isClient) {
            Function<Entity, PlayerGeoState> fn = clientLookup;
            return fn == null ? null : fn.apply(self);
        }
        return GeoidServer.get().peekState(self.getUuid());
    }
}
