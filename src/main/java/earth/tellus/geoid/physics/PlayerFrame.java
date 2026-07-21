package earth.tellus.geoid.physics;

import earth.tellus.geoid.math.Quat;
import earth.tellus.geoid.math.Vec3;
import earth.tellus.geoid.world.PlayerGeoState;

/**
 * Turns a player's abstract reference-frame {@link Quat} into the concrete quantities the renderer and
 * input mapper need.
 *
 * <p>Minecraft has no concept of a rolled horizon; the camera is yaw+pitch only. During a core
 * traversal the frame rolls up to 180 degrees, so we expose:
 * <ul>
 *   <li>{@link #cameraRollDegrees} — fed into a camera mixin to physically roll the view; and</li>
 *   <li>{@link #localUp} — the direction the player currently considers "up", used to remap movement
 *       input so W/A/S/D and jump/sneak stay intuitive after the flip.</li>
 * </ul>
 */
public final class PlayerFrame {

    private PlayerFrame() {
    }

    /** The player's "up" direction in Minecraft space, obtained by rotating +Y by the frame quat. */
    public static Vec3 localUp(PlayerGeoState state) {
        return state.frame.rotate(Vec3.UNIT_Y).normalize();
    }

    /**
     * Roll angle (degrees) the camera must apply so the horizon matches the player's frame.
     * Derived from how far the frame has tilted the local up away from world +Y about the X axis.
     */
    public static double cameraRollDegrees(PlayerGeoState state) {
        Vec3 up = localUp(state);
        // Roll is the tilt of "up" in the Y-Z plane (we flip about local east / X during traversal).
        double roll = Math.toDegrees(Math.atan2(up.z, up.y));
        return roll;
    }

    /**
     * Remaps a desired movement vector expressed in the player's local frame back into Minecraft world
     * space, so pressing "forward" moves along the surface (or tunnel wall) regardless of frame roll.
     */
    public static Vec3 localToWorld(PlayerGeoState state, Vec3 localMove) {
        return state.frame.rotate(localMove);
    }

    /** Blends interpolation for the client between two frames to keep the roll perfectly smooth. */
    public static Quat interpolate(Quat previous, Quat current, double tickDelta) {
        return Quat.slerp(previous, current, tickDelta);
    }
}
