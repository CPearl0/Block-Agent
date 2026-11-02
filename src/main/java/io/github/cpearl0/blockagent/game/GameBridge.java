package io.github.cpearl0.blockagent.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.cpearl0.blockagent.core.BotConfig;
import io.github.cpearl0.blockagent.core.CancellationToken;
import io.github.cpearl0.blockagent.core.ToolExecutor;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** All game reads and mutations are serialized on the client tick thread. */
public final class GameBridge implements ToolExecutor {
    public interface Action extends AutoCloseable {
        /** Null means that the action is still running. */
        JsonObject tick();

        /** Pause a world action so a host reflex can move or eat, then keep the same action. */
        default void yieldToHost() {}

        @Override
        default void close() {}
    }

    private static final class Request {
        final String name;
        final JsonObject args;
        final CancellationToken token, root;
        final CompletableFuture<JsonObject> result;
        final long generation;
        Object world, player;
        AutoCloseable registration;

        Request(
                String name,
                JsonObject args,
                CancellationToken token,
                CompletableFuture<JsonObject> result,
                long generation) {
            this.name = name;
            this.args = args;
            this.token = token;
            this.root = token.root();
            this.result = result;
            this.generation = generation;
        }

        void unregister() {
            if (registration != null)
                try {
                    registration.close();
                } catch (Exception ignored) {
                }
        }
    }

    private final ConcurrentLinkedQueue<Request> queue = new ConcurrentLinkedQueue<>();

    private record Session(Object world, Object player) {}

    /** Accessed only on the client thread; command roots outlive individual action deadlines. */
    private final Map<CancellationToken, Session> sessions = new WeakHashMap<>();

    private final AtomicLong generation = new AtomicLong();
    private final HostSurvival hostSurvival = new HostSurvival();
    private volatile BotConfig.Survival survival = BotConfig.Survival.defaults();
    private volatile boolean taskRunning;
    private volatile Request request;
    private Action action;
    private volatile Object world, player;

    /** Client thread reads these; the mod tick publishes the loaded config and busy flag. */
    public void configureSurvival(BotConfig.Survival survival, boolean taskRunning) {
        this.survival = survival == null ? BotConfig.Survival.defaults() : survival;
        this.taskRunning = taskRunning;
    }

    @Override
    public JsonArray definitions() {
        return ToolDefinitions.all();
    }

    @Override
    public CompletableFuture<JsonObject> execute(
            String name, JsonObject args, CancellationToken token) {
        var result = new CompletableFuture<JsonObject>();
        token.throwIfCancelled();
        var mc = Minecraft.getInstance();
        var pending = new Request(name, args.deepCopy(), token, result, generation.get());
        pending.registration = token.onCancel(() -> mc.execute(() -> cancelRequest(pending)));
        if (result.isDone()) pending.unregister();
        mc.execute(
                () -> {
                    if (result.isDone()) return;
                    if (token.isCancelled() || pending.generation != generation.get()) {
                        cancelRequest(pending);
                        return;
                    }
                    Session session =
                            sessions.computeIfAbsent(
                                    pending.root, key -> new Session(mc.level, mc.player));
                    if (session.world != mc.level || session.player != mc.player) {
                        pending.root.cancel();
                        cancelRequest(pending);
                        return;
                    }
                    pending.world = session.world;
                    pending.player = session.player;
                    if (queue.size() >= 64) {
                        pending.unregister();
                        result.complete(
                                failure("game_queue_full", "Too many queued game actions", null));
                        return;
                    }
                    queue.add(pending);
                });
        return result;
    }

    @Override
    public void cancel() {
        long before = generation.incrementAndGet();
        Minecraft.getInstance()
                .execute(
                        () -> {
                            if (request != null && request.generation < before) abortActive();
                            for (Request pending : queue)
                                if (pending.generation < before) cancelRequest(pending);
                        });
    }

    private void abortActive() {
        Request old = request;
        Action cleanup = action;
        request = null;
        action = null;
        try {
            if (cleanup != null) cleanup.close();
        } catch (RuntimeException error) {
            Navigation.stop(Minecraft.getInstance());
        } finally {
            if (old != null) {
                old.unregister();
                old.result.completeExceptionally(
                        new CancellationException("Game action cancelled"));
            }
        }
    }

    private void cancelRequest(Request pending) {
        if (request == pending) abortActive();
        else {
            queue.remove(pending);
            pending.unregister();
            pending.result.completeExceptionally(
                    new CancellationException("Game request cancelled or session changed"));
        }
    }

    public void tick() {
        var mc = Minecraft.getInstance();
        BotConfig.Survival policy = survival;
        syncSession(mc, policy);
        if (mc.player != null && mc.level != null) hostSurvival.tickDeath(mc, policy, taskRunning);
        if (request == null) {
            request = queue.poll();
            if (request == null) {
                if (mc.player != null && mc.player.isAlive())
                    hostSurvival.tickLiving(mc, policy, taskRunning, "");
                return;
            }
        }
        try {
            request.token.throwIfCancelled();
            if (mc.player == null || mc.level == null || mc.gameMode == null) {
                finish(failure("not_connected", "Join a world before running game tools", null));
                return;
            }
            if (!mc.player.isAlive()) {
                finish(failure("player_dead", "Player is dead", null));
                return;
            }
            HostSurvival.Decision decision =
                    hostSurvival.tickLiving(mc, policy, taskRunning, request.name);
            if (decision.preempt()) {
                if (action != null) action.yieldToHost();
                if (decision.interrupt())
                    finish(
                            failure(
                                    "threat_interrupted",
                                    "Host paused this action and backed away from a nearby threat. Continue the same goal from a safer position.",
                                    decision.evidence()));
                return;
            }
            if (action == null) action = create(mc, request.name, request.args, request.token);
            JsonObject result = action.tick();
            if (request == null) return;
            if (request.world != mc.level || request.player != mc.player) {
                Request invalid = request;
                invalid.root.cancel();
                cancelRequest(invalid);
                return;
            }
            request.token.throwIfCancelled();
            if (result != null) finish(result);
        } catch (CancellationException error) {
            abortActive();
        } catch (Exception error) {
            finish(
                    failure(
                            "game_action_failed",
                            error.getMessage() == null
                                    ? error.getClass().getSimpleName()
                                    : error.getMessage(),
                            null));
        }
    }

    /**
     * A same-dimension respawn replaces the client player object. Adopt that player and fail only
     * the in-flight action so the command token stays alive. Disconnects and dimension changes
     * still cancel.
     */
    private void syncSession(Minecraft mc, BotConfig.Survival policy) {
        if (world == mc.level && player == mc.player) return;
        if (canAdopt(mc, policy)) {
            boolean stale =
                    request != null && (request.player != mc.player || request.world != mc.level);
            for (var binding : sessions.entrySet()) {
                if (sameDimension(binding.getValue().world, mc.level))
                    binding.setValue(new Session(mc.level, mc.player));
                else binding.getKey().cancel();
            }
            retarget(request, mc);
            for (Request pending : queue) retarget(pending, mc);
            world = mc.level;
            player = mc.player;
            if (stale)
                finish(
                        failure(
                                "player_respawned",
                                "Player respawned; repeat the action against the fresh player",
                                null));
            return;
        }
        for (var binding : sessions.entrySet())
            if (binding.getValue().world != mc.level || binding.getValue().player != mc.player)
                binding.getKey().cancel();
        if (request != null && (request.world != mc.level || request.player != mc.player)) {
            request.root.cancel();
            abortActive();
        }
        for (Request pending : queue)
            if (pending.world != mc.level || pending.player != mc.player) {
                pending.root.cancel();
                cancelRequest(pending);
            }
        world = mc.level;
        player = mc.player;
    }

    private boolean canAdopt(Minecraft mc, BotConfig.Survival policy) {
        if (!policy.autoResumeAfterDeath() || !taskRunning) return false;
        if (!(world instanceof ClientLevel previous) || player == null) return false;
        if (mc.player == null || mc.level == null || !mc.player.isAlive()) return false;
        if (mc.level.getLevelData().isHardcore()) return false;
        return previous.dimension().equals(mc.level.dimension());
    }

    private static boolean sameDimension(Object previous, ClientLevel current) {
        return previous instanceof ClientLevel level
                && level.dimension().equals(current.dimension());
    }

    private static void retarget(Request pending, Minecraft mc) {
        if (pending == null || mc.level == null || mc.player == null) return;
        if (pending.world instanceof ClientLevel level
                && !level.dimension().equals(mc.level.dimension())) return;
        pending.world = mc.level;
        pending.player = mc.player;
    }

    private Action create(Minecraft mc, String name, JsonObject args, CancellationToken token) {
        return switch (name) {
            case "observe" -> once(() -> ok("Fresh client observation", WorldData.snapshot(mc)));
            case "inspect_block" ->
                    once(
                            () ->
                                    ok(
                                            "Loaded block state",
                                            WorldData.block(
                                                    mc, WorldData.position(args, "position"))));
            case "find_blocks" ->
                    once(() -> ok("Loaded block search", WorldData.findBlocks(mc, args)));
            case "survey_area" -> once(() -> ok("Loaded area survey", WorldData.survey(mc, args)));
            case "query_registry" ->
                    once(() -> ok("Current client registry query", WorldData.registry(mc, args)));
            case "find_standable_positions", "find_valid_placements" ->
                    once(
                            () ->
                                    ok(
                                            "Loaded candidate positions; routes unverified",
                                            WorldData.candidates(
                                                    mc,
                                                    args,
                                                    name.equals("find_valid_placements"))));
            case "move_to" -> new Navigation.Move(mc, args, token);
            default -> NativeActions.create(mc, name, args, token);
        };
    }

    private void finish(JsonObject result) {
        var completed = request;
        Action cleanup = action;
        action = null;
        request = null;
        try {
            if (cleanup != null) cleanup.close();
        } catch (RuntimeException error) {
            Navigation.stop(Minecraft.getInstance());
            result =
                    failure(
                            "action_cleanup_failed",
                            error.getMessage() == null
                                    ? "Action cleanup failed"
                                    : error.getMessage(),
                            result);
        } finally {
            completed.unregister();
        }
        completed.result.complete(result);
    }

    public JsonObject status() {
        JsonObject value = new JsonObject();
        Request active = request;
        value.addProperty("pending", queue.size());
        value.addProperty("generation", generation.get());
        value.addProperty("activeTool", active == null ? "" : active.name);
        return value;
    }

    static Action once(java.util.function.Supplier<JsonObject> supplier) {
        return supplier::get;
    }

    public static JsonObject ok(String message, JsonElement data) {
        JsonObject value = new JsonObject();
        value.addProperty("ok", true);
        value.addProperty("message", message);
        if (data != null) value.add("data", data);
        return value;
    }

    public static JsonObject failure(String code, String message, JsonElement data) {
        JsonObject value = new JsonObject();
        value.addProperty("ok", false);
        value.addProperty("code", code);
        value.addProperty("message", message);
        if (data != null) value.add("data", data);
        return value;
    }
}
