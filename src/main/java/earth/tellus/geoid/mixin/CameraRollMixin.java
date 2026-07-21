package earth.tellus.geoid.mixin;

import earth.tellus.geoid.client.ClientGeoState;
import earth.tellus.geoid.math.Quat;
import earth.tellus.geoid.physics.PlayerFrame;
import earth.tellus.geoid.world.PlayerGeoState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rolls the camera to match the player's reference frame during a core traversal, giving the smooth
 * horizon flip that sells "digging up through the far side".
 *
 * <p>Vanilla's camera is yaw+pitch only. We post-multiply the camera's rotation quaternion (and rotate
 * its basis vectors) by a roll derived from {@link PlayerFrame#cameraRollDegrees}, interpolated between
 * ticks for smoothness. The roll is 0 on the surface and up to 180 degrees at the antipodal side, so
 * outside a traversal this mixin is a no-op.
 *
 * <p>This is the most version-sensitive hook in the mod: {@code Camera}'s internal field names
 * ({@code rotation}, {@code horizontalPlane}, {@code verticalPlane}, {@code diagonalPlane}) and the
 * {@code update(...)} signature are Yarn-mapping specific. Adjust the {@link Shadow}s to your mappings.
 */
@Environment(EnvType.CLIENT)
@Mixin(Camera.class)
public abstract class CameraRollMixin {

    @Shadow private Quaternionf rotation;
    @Shadow private Vector3f horizontalPlane;
    @Shadow private Vector3f verticalPlane;
    @Shadow private Vector3f diagonalPlane;

    @Inject(method = "update", at = @At("TAIL"))
    private void geoid$applyFrameRoll(BlockView area, Entity focusedEntity, boolean thirdPerson,
                                      boolean inverseView, float tickDelta, CallbackInfo ci) {
        PlayerGeoState state = ClientGeoState.local();
        if (state.phase == PlayerGeoState.Phase.SURFACE && state.frame == Quat.IDENTITY) {
            return; // fast path: no roll on the surface
        }
        // Interpolate the frame between the last two snapshots for a jitter-free roll.
        Quat interp = PlayerFrame.interpolate(ClientGeoState.previousFrame(), state.frame, tickDelta);
        PlayerGeoState render = new PlayerGeoState(state.geodetic);
        render.frame = interp;
        double rollDeg = PlayerFrame.cameraRollDegrees(render);
        if (Math.abs(rollDeg) < 1.0e-4) {
            return;
        }
        // Roll about the camera's forward (view Z) axis.
        Quaternionf roll = new Quaternionf().rotationZ((float) Math.toRadians(rollDeg));
        this.rotation.mul(roll);
        this.horizontalPlane.rotate(roll);
        this.verticalPlane.rotate(roll);
        this.diagonalPlane.rotate(roll);
    }

    // Kept to make the mixin's intent explicit even though MinecraftClient isn't dereferenced here.
    private static boolean clientReady() {
        return MinecraftClient.getInstance() != null;
    }
}
