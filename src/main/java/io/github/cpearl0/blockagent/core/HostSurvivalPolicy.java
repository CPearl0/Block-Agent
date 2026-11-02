package io.github.cpearl0.blockagent.core;

/**
 * Tick-local survival decisions. The game host applies the result with ordinary controls. Choosing
 * a reflex never calls the model.
 */
public final class HostSurvivalPolicy {
    /** Hostile at or inside this distance pauses dig/collect and starts a retreat. */
    public static final double PAUSE_DISTANCE = 8;

    /** Keep retreating until the nearest hostile is at least this far. */
    public static final double RELEASE_DISTANCE = 12;

    /** Food level is 0..20. Eat at or below fourteen when the game will accept food. */
    public static final int EAT_BELOW_FOOD = 14;

    /** Twelve health is six hearts. Eat when hurt if food can be used. */
    public static final float EAT_BELOW_HEALTH = 12f;

    /**
     * Continuous threat for this many client ticks ends the paused action so the same goal can
     * replan once, instead of asking the model every tick.
     */
    public static final int THREAT_INTERRUPT_TICKS = 200;

    /** How long a command waits for the host to finish a same-dimension respawn. */
    public static final long RESPAWN_WAIT_MS = 30_000L;

    public enum Reflex {
        NONE,
        RETREAT,
        EAT
    }

    public record Signals(
            boolean enabled,
            boolean alive,
            float health,
            int food,
            boolean threatened,
            double nearestHostile,
            boolean holdingRetreat,
            boolean edibleAvailable,
            boolean canEatNow) {}

    private HostSurvivalPolicy() {}

    public static Reflex choose(Signals signals) {
        if (!signals.enabled() || !signals.alive()) return Reflex.NONE;
        boolean retreat =
                signals.threatened()
                        || (signals.holdingRetreat()
                                && Double.isFinite(signals.nearestHostile())
                                && signals.nearestHostile() < RELEASE_DISTANCE);
        if (retreat) return Reflex.RETREAT;
        if (signals.edibleAvailable()
                && signals.canEatNow()
                && (signals.food() <= EAT_BELOW_FOOD || signals.health() <= EAT_BELOW_HEALTH))
            return Reflex.EAT;
        return Reflex.NONE;
    }

    /** World mutations freeze while the host retreats or eats. Fresh reads stay available. */
    public static boolean yieldsToThreat(String tool) {
        return switch (tool == null ? "" : tool) {
            case "dig_at",
                    "collect_block",
                    "pickup_items",
                    "place_block",
                    "move_to",
                    "follow_player",
                    "set_block_open",
                    "wait",
                    "eat",
                    "attack_entity",
                    "inspect_inventory_window",
                    "transfer_inventory_slot",
                    "craft_item",
                    "close_inventory_window" ->
                    true;
            default -> false;
        };
    }
}
