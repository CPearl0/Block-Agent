package io.github.cpearl0.blockagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Serial, interruptible agent. This class never accesses the Minecraft client directly. */
public final class AgentController implements AutoCloseable {
    private final Path configFile;
    private final ToolExecutor tools;
    private final Consumer<String> feedback;
    private final AgentMemory memory;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "blockagent-agent");
                        t.setDaemon(true);
                        return t;
                    });
    private final AtomicLong generation = new AtomicLong();
    private volatile BotConfig config;
    private volatile CancellationToken active;
    private volatile boolean closed, busy, autonomyArmed;
    private volatile long nextAutonomyAt;
    private volatile String lastScope = "";
    private volatile ExecutionStats visibleStats;
    private JsonObject lastResult;
    private JsonObject currentStatus =
            JsonSupport.of(
                    "state",
                    "idle",
                    "generation",
                    0,
                    "command",
                    JsonNull.INSTANCE,
                    "autonomyPaused",
                    true);

    public AgentController(
            Path configFile, Path memoryDir, ToolExecutor tools, Consumer<String> feedback)
            throws IOException {
        this.configFile = configFile;
        this.tools = Objects.requireNonNull(tools);
        this.feedback = Objects.requireNonNull(feedback);
        config = BotConfig.load(configFile);
        memory = new AgentMemory(memoryDir);
    }

    public synchronized void submit(String command) {
        enqueue(command, null, false);
    }

    public boolean isBusy() {
        return busy;
    }

    public BotConfig.Survival survival() {
        return config.survival();
    }

    private synchronized void enqueue(String command, String forcedProject, boolean autonomous) {
        if (closed) throw new IllegalStateException("Agent is closed");
        String text = Objects.requireNonNull(command).trim();
        if (text.isEmpty() || text.length() > 8000)
            throw new IllegalArgumentException("Command must contain 1..8000 characters");
        if (active != null) active.cancel();
        tools.cancel();
        long owner = generation.incrementAndGet();
        BotConfig settings = config;
        CancellationToken token =
                new CancellationToken(
                        autonomous
                                ? settings.autonomy().taskTimeoutMs()
                                : settings.agent().commandTimeoutMs());
        active = token;
        busy = true;
        if (!autonomous) autonomyArmed = true;
        String commandId = UUID.randomUUID().toString();
        long started = System.currentTimeMillis();
        ExecutionStats stats = new ExecutionStats(owner, commandId);
        visibleStats = stats;
        publish(owner, "queued", text, "等待上一动作结清", commandId);
        worker.execute(
                () -> run(text, forcedProject, commandId, started, owner, token, settings, stats));
    }

    public synchronized void stop() {
        if (closed) return;
        autonomyArmed = false;
        long owner = generation.incrementAndGet();
        if (active != null) active.cancel();
        tools.cancel();
        publish(owner, "stopping", null, "正在释放控制并等待已发出的动作结清", null);
        worker.execute(
                () -> {
                    synchronized (this) {
                        if (generation.get() == owner) {
                            busy = false;
                            active = null;
                            publish(owner, "stopped", null, "已停止；项目待办已保留", null);
                        }
                    }
                });
    }

    public synchronized void reload() throws IOException {
        stop();
        BotConfig replacement = BotConfig.load(configFile);
        config = replacement;
    }

    /**
     * Only explicitly enabled, previously armed project continuation may schedule work. No startup
     * API call.
     */
    public synchronized void tick() {
        if (closed
                || busy
                || !autonomyArmed
                || !config.autonomy().enabled()
                || lastScope.isBlank()
                || System.nanoTime() < nextAutonomyAt) return;
        nextAutonomyAt =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(config.autonomy().decisionIntervalMs());
        // Memory lives on the serial worker; recheck ownership before dispatching the result.
        long owner = generation.get();
        worker.execute(
                () -> {
                    JsonArray pending = memory.projects(lastScope);
                    if (pending.isEmpty()) return;
                    JsonObject project = pending.get(0).getAsJsonObject();
                    synchronized (this) {
                        if (!closed && !busy && autonomyArmed && generation.get() == owner)
                            enqueue(
                                    "继续项目：" + JsonSupport.string(project, "objective", ""),
                                    JsonSupport.string(project, "id", ""),
                                    true);
                    }
                });
    }

    public synchronized JsonObject status() {
        JsonObject result = currentStatus.deepCopy();
        ExecutionStats stats = visibleStats;
        if (stats != null) {
            result.addProperty("executionGeneration", stats.owner);
            result.addProperty("executionCommandId", stats.commandId);
            result.addProperty("llmRequests", stats.llmRequests);
            result.addProperty("toolCalls", stats.toolCalls);
            result.addProperty("localSegments", stats.localSegments);
            result.addProperty("deathResumes", stats.deathResumes);
            result.addProperty("toolCallsIncludeHostObservations", true);
        }
        if (lastResult != null) result.add("lastResult", lastResult.deepCopy());
        return result;
    }

    private void run(
            String command,
            String forcedProject,
            String commandId,
            long started,
            long owner,
            CancellationToken token,
            BotConfig settings,
            ExecutionStats stats) {
        String projectId = null, summary = "", outcome = "failed";
        boolean accepted = false;
        EvidenceLog evidence =
                new EvidenceLog(
                        settings.agent().maxEvidenceBytes(),
                        settings.agent().renderedEvidenceBytes());
        Life life = new Life();
        try (LlmClient llm = new LlmClient(settings.provider(), line -> say(owner, line))) {
            assertCurrent(owner, token);
            settings.provider().requireConfigured();
            JsonObject initial =
                    observe(owner, token, evidence, settings, stats, life, command, commandId);
            String scope = scope(initial, commandId);
            lastScope = scope;
            memory.beginCommand(commandId, command, initial, started);
            JsonObject snapshot = initial.deepCopy();
            JsonObject project = null;
            if (forcedProject != null) {
                project = memory.attach(JsonSupport.of("id", forcedProject), scope, commandId);
                projectId = forcedProject;
            }
            String failure = "";
            JsonArray goalSummaries = new JsonArray();
            for (int replan = 0; replan <= settings.agent().maxReplans(); replan++) {
                assertCurrent(owner, token);
                publish(owner, "planning", command, "规划可验收的结果", commandId);
                JsonObject context =
                        context(
                                command, commandId, started, initial, snapshot, evidence, settings,
                                stats);
                context.add("knownProjects", memory.projects(scope));
                context.add("recentEpisodes", memory.episodes());
                context.addProperty("replanReason", failure);
                if (project != null) context.add("activeProject", project.deepCopy());
                stats.llmRequests++;
                JsonObject plan = llm.complete(AgentPrompts.PLAN, context, token);
                assertCurrent(owner, token);
                JsonObject afterPlanning =
                        observe(owner, token, evidence, settings, stats, life, command, commandId);
                requireSameWorld(initial, afterPlanning);
                snapshot = afterPlanning;
                JsonArray goals = requireArray(plan, "goals");
                if (goals.isEmpty() || goals.size() > settings.agent().maxGoals())
                    throw new IllegalArgumentException("Plan goal count exceeds configured limits");
                if (plan.has("project")) {
                    JsonObject request = JsonSupport.object(plan.get("project"), "project");
                    if (!request.has("id")) request.addProperty("originalCommand", command);
                    if (project != null
                            && (!request.has("id")
                                    || !projectId.equals(JsonSupport.string(request, "id", ""))))
                        throw new IllegalArgumentException(
                                "A replan cannot replace the original active project's stage");
                    project = memory.attach(request, scope, commandId);
                    projectId = JsonSupport.string(project, "id", "");
                }
                boolean invalidated = false;
                goalSummaries = new JsonArray();
                for (JsonElement item : goals) {
                    JsonObject goal = JsonSupport.object(item, "Goal");
                    String objective = JsonSupport.string(goal, "objective", "").trim();
                    if (objective.isEmpty() || objective.length() > 4000)
                        throw new IllegalArgumentException(
                                "Goal requires an objective of at most 4000 characters");
                    GoalResult result =
                            executeGoal(
                                    goal, command, commandId, started, owner, token, settings, llm,
                                    initial, snapshot, evidence, project, stats, life);
                    snapshot = result.snapshot;
                    goalSummaries.add(result.summary);
                    if (result.replan) {
                        failure = result.summary;
                        invalidated = true;
                        break;
                    }
                }
                if (invalidated) continue;
                snapshot =
                        observe(owner, token, evidence, settings, stats, life, command, commandId);
                publish(owner, "reviewing", command, "独立核对原指令与真实回执", commandId);
                requireSameWorld(initial, snapshot);
                JsonObject reviewInput =
                        context(
                                command, commandId, started, initial, snapshot, evidence, settings,
                                stats);
                reviewInput.add("goalSummaries", goalSummaries);
                boolean fullProjectReview =
                        project != null && project.getAsJsonArray("tasks").size() == 1;
                if (project != null) {
                    reviewInput.add("activeProject", project);
                    reviewInput.addProperty("exactStageAcceptanceTarget", firstStage(project));
                    reviewInput.addProperty("fullProjectCompletionRequired", fullProjectReview);
                    if (fullProjectReview) {
                        reviewInput.addProperty(
                                "originalProjectCommand",
                                JsonSupport.string(
                                        project,
                                        "originalCommand",
                                        JsonSupport.string(project, "objective", "")));
                        reviewInput.addProperty(
                                "fullProjectObjective",
                                JsonSupport.string(project, "objective", ""));
                    }
                }
                stats.llmRequests++;
                JsonObject review = llm.complete(AgentPrompts.REVIEW, reviewInput, token);
                assertCurrent(owner, token);
                summary = JsonSupport.string(review, "summary", "").trim();
                String status = JsonSupport.string(review, "status", "");
                if (status.equals("complete")) {
                    if (fullProjectReview
                            && !JsonSupport.bool(review, "projectObjectiveReviewed", false)) {
                        failure =
                                "Last stage needs an explicit evidence-based review of the original full project objective and constraints, not only the current stage";
                        continue;
                    }
                    JsonArray requirements = requireArray(review, "requirements");
                    if (requirements.isEmpty())
                        throw new IllegalArgumentException(
                                "Completion review returned no requirements");
                    for (JsonElement value : requirements) {
                        JsonObject requirement = JsonSupport.object(value, "Review requirement");
                        if (!JsonSupport.bool(requirement, "satisfied", false)
                                || JsonSupport.string(requirement, "requirement", "").isBlank()
                                || JsonSupport.string(requirement, "evidence", "").isBlank())
                            throw new IllegalArgumentException(
                                    "Completion review has an unsatisfied or unevidenced requirement");
                    }
                    accepted = true;
                    outcome = "succeeded";
                    break;
                }
                if (status.equals("blocked")) throw new IllegalStateException("任务受阻：" + summary);
                if (!status.equals("incomplete"))
                    throw new IllegalArgumentException("Invalid completion review status");
                failure = "Completion review found unmet requirements: " + summary;
            }
            assertCurrent(owner, token);
            if (!accepted)
                throw new IllegalStateException(
                        "Replanning limit reached; remaining requirements: " + failure);
            memory.attempt(projectId, commandId, outcome, summary, true);
            memory.episode(command, outcome, summary);
            String message =
                    project != null && project.getAsJsonArray("tasks").size() > 1
                            ? "阶段完成，后续项目待办已保存：" + summary
                            : "任务完成：" + summary;
            publish(owner, "completed", command, message, commandId);
            say(owner, message);
        } catch (CancellationException ex) {
            outcome = "cancelled";
            summary = "任务被用户指令、停止或期限取消；已完成的世界变化与项目待办保留";
            persistFailure(projectId, commandId, command, outcome, summary);
        } catch (Exception ex) {
            outcome = token.isCancelled() ? "cancelled" : "failed";
            summary = token.isCancelled() ? "任务取消；已完成的世界变化与项目待办保留" : safeMessage(ex);
            persistFailure(projectId, commandId, command, outcome, summary);
            if (generation.get() == owner && !outcome.equals("cancelled")) {
                publish(owner, "failed", command, summary, commandId);
                say(owner, "任务未完成：" + summary);
            }
        } finally {
            token.close();
            synchronized (this) {
                lastResult =
                        JsonSupport.of(
                                "generation",
                                owner,
                                "commandId",
                                commandId,
                                "outcome",
                                outcome,
                                "summary",
                                summary.length() > 1000
                                        ? summary.substring(0, 980) + "… [truncated]"
                                        : summary,
                                "llmRequests",
                                stats.llmRequests,
                                "toolCalls",
                                stats.toolCalls,
                                "localSegments",
                                stats.localSegments,
                                "deathResumes",
                                stats.deathResumes,
                                "toolCallsIncludeHostObservations",
                                true,
                                "completedAtMs",
                                System.currentTimeMillis());
                if (generation.get() == owner) {
                    busy = false;
                    active = null;
                    nextAutonomyAt =
                            System.nanoTime()
                                    + TimeUnit.MILLISECONDS.toNanos(
                                            settings.autonomy().decisionIntervalMs());
                    if (!outcome.equals("succeeded")) autonomyArmed = false;
                    if (outcome.equals("cancelled"))
                        publish(owner, "cancelled", command, summary, commandId);
                }
            }
        }
    }

    private GoalResult executeGoal(
            JsonObject goal,
            String command,
            String commandId,
            long started,
            long owner,
            CancellationToken token,
            BotConfig settings,
            LlmClient llm,
            JsonObject initial,
            JsonObject initialSnapshot,
            EvidenceLog evidence,
            JsonObject project,
            ExecutionStats stats,
            Life life)
            throws Exception {
        ProgramInterpreter.Budget budget = new ProgramInterpreter.Budget();
        JsonObject state = new JsonObject(), snapshot = initialSnapshot;
        JsonArray program = null;
        JsonObject previousState = new JsonObject();
        String reason = "";
        int generations = 0, noProgress = 0;
        while (true) {
            assertCurrent(owner, token);
            if (program == null) {
                if (generations++ >= settings.agent().maxProgramsPerGoal())
                    return new GoalResult(
                            true, "Program generation budget exhausted: " + reason, snapshot);
                publish(
                        owner,
                        "generating",
                        command,
                        JsonSupport.string(goal, "objective", ""),
                        commandId);
                JsonObject request =
                        context(
                                command, commandId, started, initial, snapshot, evidence, settings,
                                stats);
                request.add("goal", goal);
                request.add("previousRoutineState", previousState);
                request.addProperty("repairReason", reason);
                request.add(
                        "budget",
                        JsonSupport.of(
                                "remainingGoalToolCalls",
                                settings.agent().maxToolCallsPerGoal() - budget.calls,
                                "remainingProgramGenerations",
                                settings.agent().maxProgramsPerGoal() - generations,
                                "maxToolCallsPerSegment",
                                settings.agent().maxToolCallsPerSegment()));
                if (project != null) request.add("activeProject", project);
                stats.llmRequests++;
                JsonObject generated = llm.complete(AgentPrompts.PROGRAM, request, token);
                assertCurrent(owner, token);
                program = requireArray(generated, "program");
                state = new JsonObject();
                snapshot =
                        observe(owner, token, evidence, settings, stats, life, command, commandId);
                requireSameWorld(initial, snapshot);
            }
            publish(
                    owner,
                    "executing",
                    command,
                    JsonSupport.string(goal, "objective", ""),
                    commandId);
            int beforeCalls = budget.calls;
            long beforeKnowledge = evidence.knowledgeRevision();
            JsonObject before = snapshot.deepCopy();
            try {
                stats.localSegments++;
                ProgramInterpreter interpreter =
                        new ProgramInterpreter(
                                settings.agent(),
                                stats.countedTools,
                                entry -> {
                                    assertCurrent(owner, token);
                                    evidence.add(entry);
                                });
                JsonObject result =
                        interpreter.run(
                                program,
                                snapshot,
                                state,
                                JsonSupport.of(
                                        "id",
                                        commandId,
                                        "text",
                                        command,
                                        "startedAtMs",
                                        started,
                                        "elapsedTimeMs",
                                        System.currentTimeMillis() - started),
                                token,
                                budget);
                assertCurrent(owner, token);
                snapshot =
                        observe(owner, token, evidence, settings, stats, life, command, commandId);
                requireSameWorld(initial, snapshot);
                String status = JsonSupport.string(result, "status", "");
                boolean progress =
                        !EvidenceLog.stableFacts(before).equals(EvidenceLog.stableFacts(snapshot))
                                || evidence.knowledgeRevision() != beforeKnowledge;
                if (status.equals("done")) {
                    String failed = check(goal, snapshot);
                    if (failed.isEmpty())
                        return new GoalResult(
                                false, JsonSupport.string(result, "summary", ""), snapshot);
                    noProgress = progress ? 0 : noProgress + 1;
                    if (noProgress >= 2)
                        return new GoalResult(
                                true,
                                "Repeated completion claims fail unchanged actual goal checks",
                                snapshot);
                    reason = "Fresh goal checks failed: " + failed;
                    previousState = state;
                    program = null;
                    continue;
                }
                noProgress = progress ? 0 : noProgress + 1;
                if (noProgress >= 2)
                    return new GoalResult(
                            true,
                            "Repeated local segments/replans have no new evidence or observed progress",
                            snapshot);
                if (status.equals("continue")) {
                    if (budget.calls == beforeCalls)
                        throw new ProgramInterpreter.BudgetExceeded(
                                "Continuation must perform at least one actual tool call");
                    state = result.getAsJsonObject("state").deepCopy();
                    continue;
                }
                reason = JsonSupport.string(result, "summary", "");
                previousState =
                        result.has("state") ? result.getAsJsonObject("state").deepCopy() : state;
                if (JsonSupport.string(result, "scope", "goal").equals("plan"))
                    return new GoalResult(true, reason, snapshot);
                program = null;
            } catch (CancellationException ex) {
                throw ex;
            } catch (ProgramInterpreter.ToolFailure
                    | ProgramInterpreter.BudgetExceeded
                    | IllegalArgumentException
                    | IllegalStateException ex) {
                assertCurrent(owner, token);
                if (ex instanceof ProgramInterpreter.ToolFailure failure
                        && isLifeTransition(failure.receipt)) {
                    if (!settings.survival().autoResumeAfterDeath())
                        throw new IllegalStateException(
                                "Player died and autoResumeAfterDeath is disabled");
                    snapshot =
                            resumeAfterDeath(
                                    owner, token, evidence, settings, stats, life, command,
                                    commandId);
                    requireSameWorld(initial, snapshot);
                    reason = "Player died; respawned and continuing the same goal";
                    previousState = state;
                    program = null;
                    continue;
                }
                reason = safeMessage(ex);
                previousState = state;
                program = null;
                snapshot =
                        observe(owner, token, evidence, settings, stats, life, command, commandId);
                requireSameWorld(initial, snapshot);
                boolean progress =
                        !EvidenceLog.stableFacts(before).equals(EvidenceLog.stableFacts(snapshot))
                                || evidence.knowledgeRevision() != beforeKnowledge;
                noProgress = progress ? 0 : noProgress + 1;
                if (noProgress >= 2)
                    return new GoalResult(
                            true,
                            "Repeated failed segments have no new evidence or observed progress: "
                                    + reason,
                            snapshot);
                if (budget.calls >= settings.agent().maxToolCallsPerGoal())
                    return new GoalResult(true, "Goal call budget exhausted: " + reason, snapshot);
            }
        }
    }

    private JsonObject observe(
            long owner,
            CancellationToken token,
            EvidenceLog evidence,
            BotConfig settings,
            ExecutionStats stats,
            Life life,
            String command,
            String commandId)
            throws Exception {
        JsonObject result = observeOnce(owner, token, evidence, settings, stats);
        if (isLifeTransition(result)) {
            if (!settings.survival().autoResumeAfterDeath())
                throw new IllegalStateException(
                        "Fresh observe failed: "
                                + JsonSupport.string(result, "message", "Player is dead"));
            return resumeAfterDeath(
                    owner, token, evidence, settings, stats, life, command, commandId);
        }
        return acceptLiving(result, life);
    }

    /**
     * Wait until the host respawns in the same dimension, then keep this command's goal, memory and
     * program loop. A human stop cancels the token and leaves this wait immediately.
     */
    private JsonObject resumeAfterDeath(
            long owner,
            CancellationToken token,
            EvidenceLog evidence,
            BotConfig settings,
            ExecutionStats stats,
            Life life,
            String command,
            String commandId)
            throws Exception {
        if (!life.deathOpen) {
            life.deathOpen = true;
            int attempt = ++stats.deathResumes;
            if (attempt > settings.survival().maxDeathResumes())
                throw new IllegalStateException(
                        "Death resume limit reached ("
                                + settings.survival().maxDeathResumes()
                                + "); the same goal stops after repeated deaths, not a human stop");
            evidence.add(deathReceipt(attempt, life.lastLiving));
            publish(
                    owner,
                    "respawning",
                    command,
                    "玩家死亡，正在自动重生并继续同一目标（第 " + attempt + " 次）",
                    commandId);
        }
        long deadline =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(HostSurvivalPolicy.RESPAWN_WAIT_MS);
        while (true) {
            assertCurrent(owner, token);
            JsonObject result = observeOnce(owner, token, evidence, settings, stats);
            if (!isLifeTransition(result)) return acceptLiving(result, life);
            if (System.nanoTime() > deadline)
                throw new IllegalStateException(
                        "Timed out waiting to respawn and continue the same goal");
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                token.throwIfCancelled();
                throw new CancellationException("Interrupted while waiting to respawn");
            }
        }
    }

    private JsonObject observeOnce(
            long owner,
            CancellationToken token,
            EvidenceLog evidence,
            BotConfig settings,
            ExecutionStats stats)
            throws Exception {
        assertCurrent(owner, token);
        CompletableFuture<JsonObject> pending = null;
        try (CancellationToken action =
                new CancellationToken(
                        token,
                        Math.min(
                                settings.agent().actionTimeoutMs(),
                                Math.max(1, token.remainingMillis())))) {
            pending = stats.countedTools.execute("observe", new JsonObject(), action);
            JsonObject result;
            try {
                result = action.await(pending);
            } catch (CancellationException ex) {
                token.throwIfCancelled();
                throw new IllegalStateException("Fresh observation timed out", ex);
            }
            assertCurrent(owner, token);
            if (isLifeTransition(result)) return result;
            if (!JsonSupport.bool(result, "ok", false)
                    || !result.has("data")
                    || !result.get("data").isJsonObject())
                throw new IllegalStateException(
                        "Fresh observe failed: "
                                + JsonSupport.string(result, "message", "No snapshot"));
            evidence.add(
                    JsonSupport.of(
                            "tool",
                            "observe",
                            "args",
                            new JsonObject(),
                            "result",
                            result,
                            "completedAtMs",
                            System.currentTimeMillis()));
            return result;
        } finally {
            if (pending != null && !pending.isDone()) {
                tools.cancel();
                pending.handle((v, e) -> null).join();
            }
        }
    }

    private static JsonObject acceptLiving(JsonObject result, Life life) {
        JsonObject data = result.getAsJsonObject("data").deepCopy();
        life.lastLiving = data;
        life.deathOpen = false;
        return data;
    }

    private static JsonObject deathReceipt(int attempt, JsonObject lastLiving) {
        JsonObject data = JsonSupport.of("attempt", attempt, "resume", "same_command");
        if (lastLiving != null && lastLiving.has("position"))
            data.add("lastLivingPosition", lastLiving.get("position").deepCopy());
        return JsonSupport.of(
                "tool",
                "host_respawn",
                "args",
                JsonSupport.of("attempt", attempt),
                "result",
                JsonSupport.of(
                        "ok",
                        true,
                        "message",
                        "Player died. Host is respawning and the same command continues. Recover dropped items near the last living position if they are still loaded.",
                        "data",
                        data),
                "completedAtMs",
                System.currentTimeMillis());
    }

    private static boolean isLifeTransition(JsonObject result) {
        if (result == null || JsonSupport.bool(result, "ok", false)) return false;
        String code = JsonSupport.string(result, "code", "");
        return code.equals("player_dead") || code.equals("player_respawned");
    }

    private JsonObject context(
            String command,
            String commandId,
            long started,
            JsonObject initial,
            JsonObject snapshot,
            EvidenceLog evidence,
            BotConfig settings,
            ExecutionStats stats) {
        return JsonSupport.of(
                "command",
                command,
                "commandContext",
                JsonSupport.of(
                        "id",
                        commandId,
                        "startedAtMs",
                        started,
                        "elapsedTimeMs",
                        System.currentTimeMillis() - started),
                "commandOrigin",
                memory.commandOrigin(commandId),
                "initialSnapshot",
                JsonSupport.bounded(initial, 32768),
                "currentSnapshot",
                JsonSupport.bounded(snapshot, 32768),
                "tools",
                tools.definitions(),
                "historicalEvidence",
                evidence.snapshot(),
                "maxGoals",
                settings.agent().maxGoals(),
                "hostSurvival",
                JsonSupport.of(
                        "autoResumeAfterDeath",
                        settings.survival().autoResumeAfterDeath(),
                        "hostThreatPolicy",
                        settings.survival().hostThreatPolicy(),
                        "deathResumes",
                        stats.deathResumes,
                        "maxDeathResumes",
                        settings.survival().maxDeathResumes()));
    }

    private static String check(JsonObject goal, JsonObject snapshot) {
        if (!goal.has("checks")) return "";
        JsonArray checks = requireArray(goal, "checks");
        if (checks.size() > 64) throw new IllegalArgumentException("Too many goal checks");
        for (JsonElement value : checks) {
            JsonObject c = JsonSupport.object(value, "Goal check");
            String path = JsonSupport.string(c, "path", "");
            JsonElement actual = checkValue(snapshot, path), expected = c.get("value");
            String op = JsonSupport.string(c, "op", "");
            boolean matches;
            if (expected == null || actual.isJsonNull()) matches = false;
            else if (op.equals("eq")) matches = actual.equals(expected);
            else if (op.equals("ne")) matches = !actual.equals(expected);
            else if (actual.isJsonPrimitive()
                    && actual.getAsJsonPrimitive().isNumber()
                    && expected.isJsonPrimitive()
                    && expected.getAsJsonPrimitive().isNumber()) {
                int comparison = actual.getAsBigDecimal().compareTo(expected.getAsBigDecimal());
                matches =
                        switch (op) {
                            case "gte" -> comparison >= 0;
                            case "lte" -> comparison <= 0;
                            case "gt" -> comparison > 0;
                            case "lt" -> comparison < 0;
                            default ->
                                    throw new IllegalArgumentException(
                                            "Unknown goal check operator");
                        };
            } else matches = false;
            if (!matches) return path + " " + op + " " + expected + "; actual=" + actual;
        }
        return "";
    }

    private void assertCurrent(long owner, CancellationToken token) {
        token.throwIfCancelled();
        if (closed || generation.get() != owner)
            throw new CancellationException("Stale command generation");
    }

    private static JsonElement checkValue(JsonObject snapshot, String path) {
        if (path.startsWith("inventory.")
                && snapshot.has("inventory")
                && snapshot.get("inventory").isJsonArray()) {
            String itemName = path.substring(10);
            long count = 0;
            for (JsonElement entry : snapshot.getAsJsonArray("inventory"))
                if (entry.isJsonObject()) {
                    JsonObject item = entry.getAsJsonObject();
                    String name =
                            JsonSupport.string(item, "name", JsonSupport.string(item, "id", ""));
                    if (name.equals(itemName))
                        count += JsonSupport.number(item, "count", 0, 0, Integer.MAX_VALUE);
                }
            return new JsonPrimitive(count);
        }
        return JsonSupport.path(snapshot, path);
    }

    private static void requireSameWorld(JsonObject initial, JsonObject current) {
        for (String key : List.of("serverId", "serverAddress", "server", "dimension"))
            if (initial.has(key) && !initial.get(key).equals(current.get(key)))
                throw new IllegalStateException(
                        "Client server/dimension changed; discard old coordinate decisions and resume project after a fresh command");
    }

    private synchronized void publish(
            long owner, String state, String command, String detail, String commandId) {
        if (generation.get() != owner) return;
        currentStatus =
                JsonSupport.of(
                        "state",
                        state,
                        "generation",
                        owner,
                        "command",
                        command,
                        "commandId",
                        commandId,
                        "detail",
                        detail,
                        "updatedAtMs",
                        System.currentTimeMillis(),
                        "autonomyPaused",
                        !autonomyArmed);
    }

    private void say(long owner, String text) {
        if (generation.get() != owner) return;
        try {
            feedback.accept(text);
        } catch (RuntimeException ignored) {
        }
    }

    private void persistFailure(
            String projectId, String commandId, String command, String outcome, String summary) {
        try {
            memory.attempt(projectId, commandId, outcome, summary, false);
            memory.episode(command, outcome, summary);
        } catch (IOException ex) {
            try {
                feedback.accept("项目记录保存失败：" + safeMessage(ex));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String firstStage(JsonObject project) {
        JsonArray tasks = project.getAsJsonArray("tasks");
        if (tasks.isEmpty()) throw new IllegalArgumentException("Project already complete");
        return tasks.get(0).getAsString();
    }

    private static String scope(JsonObject snapshot, String commandId) {
        String server =
                JsonSupport.string(
                        snapshot,
                        "serverId",
                        JsonSupport.string(
                                snapshot,
                                "serverAddress",
                                JsonSupport.string(snapshot, "server", "")));
        String dimension = JsonSupport.string(snapshot, "dimension", "");
        return server.isBlank() || dimension.isBlank()
                ? "unscoped:" + commandId
                : server + "|" + dimension;
    }

    private static JsonArray requireArray(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray())
            throw new IllegalArgumentException("Missing array: " + key);
        return o.getAsJsonArray(key);
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) message = ex.getClass().getSimpleName();
        return message.length() > 1000 ? message.substring(0, 980) + "… [truncated]" : message;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        autonomyArmed = false;
        closed = true;
        generation.incrementAndGet();
        if (active != null) active.cancel();
        tools.cancel();
        worker.shutdown();
        currentStatus = JsonSupport.of("state", "closed", "generation", generation.get());
    }

    private record GoalResult(boolean replan, String summary, JsonObject snapshot) {}

    /** Per-command death resume. One open death counts once until a living snapshot returns. */
    private static final class Life {
        boolean deathOpen;
        JsonObject lastLiving;
    }

    private final class ExecutionStats {
        final long owner;
        final String commandId;
        volatile int llmRequests, toolCalls, localSegments, deathResumes;
        final ToolExecutor countedTools =
                new ToolExecutor() {
                    public JsonArray definitions() {
                        return tools.definitions();
                    }

                    public CompletableFuture<JsonObject> execute(
                            String name, JsonObject args, CancellationToken token) {
                        assertCurrent(owner, token);
                        toolCalls++;
                        return tools.execute(name, args, token);
                    }

                    public void cancel() {
                        tools.cancel();
                    }
                };

        ExecutionStats(long owner, String commandId) {
            this.owner = owner;
            this.commandId = commandId;
        }
    }
}
