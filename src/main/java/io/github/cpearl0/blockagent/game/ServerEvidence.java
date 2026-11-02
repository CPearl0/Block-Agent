package io.github.cpearl0.blockagent.game;

import io.github.cpearl0.blockagent.game.mixin.ClientLevelAccessor;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ClientLevel;

/** Client thread only; acknowledgements are recorded after vanilla applies server corrections. */
public final class ServerEvidence {
    private static final Map<ClientLevel, Integer> ACKS = new WeakHashMap<>();
    private static final Map<ClientLevel, Map<Integer, Long>> CONTENT = new WeakHashMap<>();
    private static final Map<ClientLevel, Long> INVENTORY = new WeakHashMap<>();

    private ServerEvidence() {}

    public static void acknowledged(ClientLevel world, int sequence) {
        ACKS.merge(world, sequence, Math::max);
    }

    public static int sequence(ClientLevel world) {
        return ((ClientLevelAccessor) world).blockagent$predictionHandler().currentSequence();
    }

    public static boolean confirmed(ClientLevel world, int sequence) {
        return ACKS.getOrDefault(world, -1) >= sequence;
    }

    public static void inventoryUpdated(ClientLevel world) {
        if (world != null) INVENTORY.merge(world, 1L, Long::sum);
    }

    public static long inventoryRevision(ClientLevel world) {
        return INVENTORY.getOrDefault(world, 0L);
    }

    public static void contentUpdated(ClientLevel world, int containerId) {
        if (world != null) {
            CONTENT.computeIfAbsent(world, key -> new java.util.HashMap<>())
                    .merge(containerId, 1L, Long::sum);
            inventoryUpdated(world);
        }
    }

    public static long contentRevision(ClientLevel world, int containerId) {
        return CONTENT.getOrDefault(world, Map.of()).getOrDefault(containerId, 0L);
    }
}
