package earth.tellus.geoid.mixin;

import earth.tellus.geoid.physics.GeoidGravity;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla's hard-coded downward gravity with the spherical engine's gravity — but only when
 * the engine actually wants to take over (a player mid core-traversal). In every other case this hook
 * returns immediately and vanilla physics runs untouched, so normal surface play is unaffected.
 *
 * <p>Targets {@code Entity#applyGravity()} (the gravity step factored out in MC 1.21.3+). On the near
 * side of a core traversal the applied gravity is {@code -Y} exactly like vanilla; the interesting part
 * is past the centre, where {@link GeoidGravity} applies {@code +Y} gravity so the player is pulled back
 * toward the (now overhead) centre — the physical basis of the 180-degree flip.
 *
 * <p>For MC versions before {@code applyGravity()} existed, retarget this to
 * {@code LivingEntity#travel} and modify {@code velocity.y} there instead; the {@link GeoidGravity}
 * logic is unchanged.
 */
@Mixin(Entity.class)
public abstract class EntityGravityMixin {

    @Inject(method = "applyGravity", at = @At("HEAD"), cancellable = true)
    private void geoid$sphericalGravity(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (GeoidGravity.apply(self)) {
            ci.cancel(); // we handled gravity for this tick
        }
    }
}
