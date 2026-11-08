package io.github.cpearl0.blockagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HostSurvivalPolicyTest {
    @Test
    void lowAirSurfacesBeforeDrowningButDoesNotJumpOnLand() {
        assertTrue(HostSurvivalPolicy.needsAir(true, 199));
        assertTrue(HostSurvivalPolicy.needsAir(true, 0));
        assertFalse(HostSurvivalPolicy.needsAir(true, 200));
        assertFalse(HostSurvivalPolicy.needsAir(true, 300));
        assertFalse(HostSurvivalPolicy.needsAir(false, 0));
    }

    @Test
    void retreatBeatsHungerAndHoldsUntilTheHostileIsClear() {
        HostSurvivalPolicy.Signals threatened = signals(true, 8f, 6, true, 4, false, true, true);
        assertEquals(HostSurvivalPolicy.Reflex.RETREAT, HostSurvivalPolicy.choose(threatened));

        HostSurvivalPolicy.Signals stillClose =
                signals(true, 20f, 20, false, 10, true, false, false);
        assertEquals(HostSurvivalPolicy.Reflex.RETREAT, HostSurvivalPolicy.choose(stillClose));

        HostSurvivalPolicy.Signals clear = signals(true, 20f, 20, false, 13, true, false, false);
        assertEquals(HostSurvivalPolicy.Reflex.NONE, HostSurvivalPolicy.choose(clear));

        HostSurvivalPolicy.Signals disabled = signals(false, 4f, 2, true, 2, false, true, true);
        assertEquals(HostSurvivalPolicy.Reflex.NONE, HostSurvivalPolicy.choose(disabled));
    }

    @Test
    void eatsWhenHungryOrHurtAndWorldReadsDoNotYield() {
        assertEquals(
                HostSurvivalPolicy.Reflex.EAT,
                HostSurvivalPolicy.choose(signals(true, 20f, 10, false, 30, false, true, true)));
        assertEquals(
                HostSurvivalPolicy.Reflex.EAT,
                HostSurvivalPolicy.choose(signals(true, 8f, 18, false, 40, false, true, true)));
        assertEquals(
                HostSurvivalPolicy.Reflex.NONE,
                HostSurvivalPolicy.choose(signals(true, 20f, 18, false, 40, false, true, false)));
        assertEquals(
                HostSurvivalPolicy.Reflex.NONE,
                HostSurvivalPolicy.choose(signals(true, 20f, 20, false, 40, false, false, false)));

        assertTrue(HostSurvivalPolicy.yieldsToThreat("dig_at"));
        assertTrue(HostSurvivalPolicy.yieldsToThreat("smelt_item"));
        assertTrue(HostSurvivalPolicy.yieldsToThreat("collect_block"));
        assertTrue(HostSurvivalPolicy.yieldsToThreat("move_to"));
        assertFalse(HostSurvivalPolicy.yieldsToThreat("observe"));
        assertFalse(HostSurvivalPolicy.yieldsToThreat("inspect_block"));
        assertFalse(HostSurvivalPolicy.yieldsToThreat("find_blocks"));
        assertFalse(HostSurvivalPolicy.yieldsToThreat(null));
    }

    private static HostSurvivalPolicy.Signals signals(
            boolean enabled,
            float health,
            int food,
            boolean threatened,
            double nearest,
            boolean holdingRetreat,
            boolean edible,
            boolean canEatNow) {
        return new HostSurvivalPolicy.Signals(
                enabled,
                true,
                health,
                food,
                threatened,
                nearest,
                holdingRetreat,
                edible,
                canEatNow);
    }
}
