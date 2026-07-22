package earth.tellus.geoid.physics;

import earth.tellus.geoid.math.GravityField;
import earth.tellus.geoid.math.Vec3;
import earth.tellus.geoid.world.CoreTunnel;
import earth.tellus.geoid.world.PlayerGeoState;

/**
 * The custom gravity integrator that overrides Minecraft's hard-coded {@code velocity.y -= 0.08}.
 *
 * <p>The class is intentionally thin and stateless: it takes the player's authoritative
 * {@link PlayerGeoState} plus their current velocity and returns the velocity after one tick of
 * spherical gravity. The mixin (see {@code EntityGravityMixin}) calls this and writes the result back,
 * so all the branching physics lives here in plain, testable code instead of scattered across hooks.
 *
 * <p>Design rule: on the surface the output is numerically identical to vanilla, so nothing about
 * ordinary play changes. The interesting behaviour only switches on during a core traversal.
 */
public final class SphericalPhysics {

    /** Vanilla vertical air drag applied after gravity. */
    public static final double AIR_DRAG = 0.98;

    private SphericalPhysics() {
    }

    /**
     * Returns the gravitational acceleration to add to velocity this tick, in Minecraft space.
     *
     * @param state     the player's spherical state (never null)
     * @param traversal the active core traversal, or {@code null} on the surface
     */
    public static Vec3 gravityAcceleration(PlayerGeoState state, CoreTunnel traversal) {
        if (state.phase == PlayerGeoState.Phase.SURFACE || traversal == null) {
            // Surface: local down is -Y; magnitude follows altitude (inverse-square above surface).
            Vec3 g = GravityField.accelerationLocal(state.geodetic);
            return g; // typically (0, -0.08, 0)
        }
        double gy = traversal.gravityY(state.coreParam);
        return new Vec3(0.0, gy, 0.0);
    }

    /**
     * Applies gravity + vanilla-style vertical drag to a velocity for one tick.
     * Kept branch-for-branch faithful to vanilla on the surface: {@code (vy - 0.08) * 0.98}.
     */
    public static Vec3 integrate(PlayerGeoState state, CoreTunnel traversal, Vec3 velocity, boolean noGravity) {
        if (noGravity) {
            return velocity;
        }
        Vec3 g = gravityAcceleration(state, traversal);
        double vy = (velocity.y + g.y) * AIR_DRAG;
        // Horizontal gravity components are only ever non-zero in exotic frames; apply them too so the
        // model stays correct if a non-vertical gravity direction is ever introduced.
        double vx = g.x != 0.0 ? (velocity.x + g.x) : velocity.x;
        double vz = g.z != 0.0 ? (velocity.z + g.z) : velocity.z;
        return new Vec3(vx, vy, vz);
    }

    /**
     * Recomputes the core-traversal parameter {@code s} from the player's absolute Y position inside
     * the compressed {@code geoid:core} dimension.
     *
     * <p>This is deliberately <em>not</em> an incremental integration of per-tick deltas: the interior
     * is visually compressed ({@link CoreTunnel#sToVisualDepth}), so one block of real Y movement can
     * represent anywhere from one block to several thousand blocks of true diameter depending on how
     * deep the player is. Deriving {@code s} fresh each tick via {@link CoreTunnel#visualDepthToS} keys
     * the compression ratio exactly to position rather than accumulating drift, and self-corrects if the
     * player is pushed or knocked back.
     *
     * @param coreDimensionY     the player's current Y inside the {@code geoid:core} dimension
     * @param coreDimensionEntryY the Y at which this traversal entered the dimension (visual depth 0)
     * @param coreEntryDepth     the real dig depth (blocks below the origin surface) at which the
     *                           traversal began — {@code s} at entry, and still inside the 1:1 shell
     */
    public static void advanceTraversal(PlayerGeoState state, CoreTunnel traversal,
                                        double coreDimensionY, double coreDimensionEntryY, double coreEntryDepth) {
        if (traversal == null || state.phase == PlayerGeoState.Phase.SURFACE) {
            return;
        }
        double visualAtEntry = traversal.sToVisualDepth(coreEntryDepth);
        double visualDepth = visualAtEntry + (coreDimensionEntryY - coreDimensionY);
        state.coreParam = clamp(traversal.visualDepthToS(visualDepth), 0.0, earth.tellus.geoid.math.SphereMath.DIAMETER);
        state.frame = traversal.frameAt(state.coreParam);
        state.geodetic = traversal.geodeticAt(state.coreParam);
        state.phase = traversal.pastCentre(state.coreParam)
                ? PlayerGeoState.Phase.CORE_ASCENT
                : PlayerGeoState.Phase.CORE_DESCENT;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
