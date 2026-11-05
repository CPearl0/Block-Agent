package io.github.cpearl0.blockagent.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SmeltingProgressTest {
    @Test
    void existingHoldingsDoNotSatisfyTheRequest() {
        SmeltingProgress progress = new SmeltingProgress(30, 2);
        assertEquals(0, progress.produced());
        assertFalse(progress.confirm(31, 1));
        assertTrue(progress.confirm(32, 1));
        assertEquals(2, progress.produced());
    }

    @Test
    void acceptsWholeRecipeYieldAboveRequestedCount() {
        SmeltingProgress progress = new SmeltingProgress(0, 3);
        assertFalse(progress.confirm(2, 2));
        assertTrue(progress.confirm(4, 2));
        assertEquals(4, progress.produced());
    }

    @Test
    void missingPartialOrUnrelatedInventoryChangesCannotConfirmOutput() {
        SmeltingProgress progress = new SmeltingProgress(5, 2);
        for (int observed : new int[] {0, 4, 5, 6, 8}) {
            assertThrows(IllegalStateException.class, () -> progress.confirm(observed, 2));
            assertEquals(0, progress.produced());
        }
        assertTrue(progress.confirm(7, 2));
        assertThrows(IllegalStateException.class, () -> progress.confirm(7, 2));
        assertEquals(2, progress.produced());
    }

    @Test
    void rejectsInvalidRequestsAndEmptyOutput() {
        assertThrows(IllegalArgumentException.class, () -> new SmeltingProgress(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SmeltingProgress(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SmeltingProgress(0, 65));
        assertThrows(IllegalStateException.class, () -> new SmeltingProgress(0, 1).confirm(0, 0));
    }
}
