package earth.tellus.geoid.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import earth.tellus.geoid.math.Geodetic;
import earth.tellus.geoid.math.SphereMath;
import earth.tellus.geoid.world.CoreTunnel;
import earth.tellus.geoid.world.PlayerGeoState;
import org.junit.jupiter.api.Test;

/**
 * Pins down the fix that makes the core traversal actually reachable in a real game: {@code s} must be
 * derived from the player's position via {@link CoreTunnel#visualDepthToS}, not integrated 1:1 from
 * raw Y deltas, or the compressed interior wouldn't be compressed at all.
 */
class SphericalPhysicsTest {

    private static final double ENTRY_Y = 1500.0;
    private static final double ENTRY_DEPTH = 480.0;

    private static CoreTunnel tunnel() {
        return new CoreTunnel(Geodetic.ofDegrees(52.2297, 21.0122, 0));
    }

    private static PlayerGeoState freshState() {
        PlayerGeoState state = new PlayerGeoState(Geodetic.ofDegrees(52.2297, 21.0122, -ENTRY_DEPTH));
        state.phase = PlayerGeoState.Phase.CORE_DESCENT;
        state.coreParam = ENTRY_DEPTH;
        return state;
    }

    @Test
    void oneBlockNearEntryMovesSByOneBlock() {
        PlayerGeoState state = freshState();
        SphericalPhysics.advanceTraversal(state, tunnel(), ENTRY_Y - 1.0, ENTRY_Y, ENTRY_DEPTH);
        assertEquals(ENTRY_DEPTH + 1.0, state.coreParam, 1.0e-6);
    }

    @Test
    void oneBlockDeepInsideMovesSByThousandsOfBlocks() {
        CoreTunnel tunnel = tunnel();
        PlayerGeoState state = freshState();
        // Descend past the 1:1 shell into the compressed interior first.
        SphericalPhysics.advanceTraversal(state, tunnel, ENTRY_Y - 100.0, ENTRY_Y, ENTRY_DEPTH);
        double sBefore = state.coreParam;
        SphericalPhysics.advanceTraversal(state, tunnel, ENTRY_Y - 101.0, ENTRY_Y, ENTRY_DEPTH);
        double sAfter = state.coreParam;
        assertTrue(sAfter - sBefore > 1000.0,
                "one block inside the compressed interior should represent thousands of real diameter "
                        + "blocks, got " + (sAfter - sBefore));
    }

    @Test
    void reachingTheBottomOfTheShaftRepresentsTheAntipodalSurface() {
        CoreTunnel tunnel = tunnel();
        PlayerGeoState state = freshState();
        double exitVisualDepth = CoreTunnel.visualShaftHeight();
        double exitY = ENTRY_Y - (exitVisualDepth - tunnel.sToVisualDepth(ENTRY_DEPTH));
        SphericalPhysics.advanceTraversal(state, tunnel, exitY, ENTRY_Y, ENTRY_DEPTH);
        assertEquals(SphereMath.DIAMETER, state.coreParam, 1.0e-3);
        assertEquals(PlayerGeoState.Phase.CORE_ASCENT, state.phase);
    }

    @Test
    void climbingBackAboveEntryReturnsTowardZero() {
        CoreTunnel tunnel = tunnel();
        PlayerGeoState state = freshState();
        SphericalPhysics.advanceTraversal(state, tunnel, ENTRY_Y - 50.0, ENTRY_Y, ENTRY_DEPTH);
        SphericalPhysics.advanceTraversal(state, tunnel, ENTRY_Y, ENTRY_Y, ENTRY_DEPTH);
        assertEquals(ENTRY_DEPTH, state.coreParam, 1.0e-6);
        assertEquals(PlayerGeoState.Phase.CORE_DESCENT, state.phase);
    }
}
