package earth.tellus.geoid.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import earth.tellus.geoid.math.EquirectangularProjection;

/**
 * Pure-logic tests for {@link WorldFolding}, including the reactive reconciliation path used when an
 * external mechanism (an Immersive Portals wrap portal) physically moves the player across the seam
 * instead of Geoid's own teleport.
 */
class WorldFoldingTest {

    private static final double EPS = 1.0e-6;

    private static WorldFolding newFolding() {
        return new WorldFolding(EquirectangularProjection.INSTANCE, 64.0, 12);
    }

    @Test
    void reconcileExternalFoldDetectsEastwardCrossing() {
        WorldFolding folding = newFolding();
        double period = EquirectangularProjection.INSTANCE.wrapPeriodX();
        double half = period * 0.5;

        // Player was just inside the east edge, and a wrap portal dropped them just past the west edge.
        double lastMcX = half - 1.0;
        double currentMcX = -half + 1.0;

        assertTrue(folding.reconcileExternalFold(lastMcX, currentMcX));
        assertEquals(period, folding.windowOffsetX(), EPS);
    }

    @Test
    void reconcileExternalFoldDetectsWestwardCrossing() {
        WorldFolding folding = newFolding();
        double period = EquirectangularProjection.INSTANCE.wrapPeriodX();
        double half = period * 0.5;

        double lastMcX = -half + 1.0;
        double currentMcX = half - 1.0;

        assertTrue(folding.reconcileExternalFold(lastMcX, currentMcX));
        assertEquals(-period, folding.windowOffsetX(), EPS);
    }

    @Test
    void reconcileExternalFoldIgnoresOrdinaryMovement() {
        WorldFolding folding = newFolding();
        assertFalse(folding.reconcileExternalFold(0.0, 5.0));
        assertEquals(0.0, folding.windowOffsetX(), EPS);
    }

    @Test
    void trueMapXStaysContinuousAcrossAnExternalFold() {
        WorldFolding folding = newFolding();
        double period = EquirectangularProjection.INSTANCE.wrapPeriodX();
        double half = period * 0.5;

        double lastMcX = half - 1.0;
        double trueMapXBefore = lastMcX + folding.windowOffsetX();

        double currentMcX = -half + 1.0; // the portal placed them 2 blocks east of the true seam
        folding.reconcileExternalFold(lastMcX, currentMcX);
        double trueMapXAfter = currentMcX + folding.windowOffsetX();

        assertEquals(trueMapXBefore + 2.0, trueMapXAfter, EPS);
    }
}
