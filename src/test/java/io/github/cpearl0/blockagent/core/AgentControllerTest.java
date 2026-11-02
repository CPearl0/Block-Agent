package io.github.cpearl0.blockagent.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentControllerTest {
    @TempDir Path directory;

    private static final class FakeTools implements ToolExecutor {
        final AtomicInteger actions = new AtomicInteger(),
                dispatches = new AtomicInteger(),
                menuReads = new AtomicInteger(),
                deathActions = new AtomicInteger();
        final AtomicLong observations = new AtomicLong();
        final AtomicBoolean stayDead = new AtomicBoolean();
        final AtomicReference<CompletableFuture<JsonObject>> pending = new AtomicReference<>();

        public JsonArray definitions() {
            JsonArray defs = new JsonArray();
            for (String name : List.of("observe", "generic_action", "inspect_inventory_window"))
                defs.add(
                        JsonSupport.of(
                                "name",
                                name,
                                "description",
                                "fixture capability",
                                "parameters",
                                JsonSupport.of("type", "object")));
            return defs;
        }

        public CompletableFuture<JsonObject> execute(
                String name, JsonObject args, CancellationToken token) {
            dispatches.incrementAndGet();
            token.throwIfCancelled();
            if (name.equals("observe")) {
                if (stayDead.get())
                    return CompletableFuture.completedFuture(
                            JsonSupport.of(
                                    "ok",
                                    false,
                                    "code",
                                    "player_dead",
                                    "message",
                                    "Player is dead"));
                long clock = observations.incrementAndGet();
                return CompletableFuture.completedFuture(
                        JsonSupport.of(
                                "ok",
                                true,
                                "message",
                                "fresh",
                                "data",
                                JsonSupport.of(
                                        "server",
                                        "fixture",
                                        "dimension",
                                        "test:world",
                                        "connected",
                                        true,
                                        "gameTime",
                                        clock,
                                        "dayTime",
                                        clock,
                                        "position",
                                        JsonSupport.of("x", 10.1 + clock * .0001, "y", 64, "z", 20),
                                        "nearbyEntities",
                                        List.of(
                                                JsonSupport.of(
                                                        "id",
                                                        42,
                                                        "name",
                                                        "example:mob",
                                                        "position",
                                                        JsonSupport.of("x", clock, "y", 64, "z", 0),
                                                        "distance",
                                                        clock)),
                                        "inventory",
                                        List.of(
                                                JsonSupport.of(
                                                        "name",
                                                        "test:part",
                                                        "count",
                                                        actions.get())))));
            }
            if (name.equals("inspect_inventory_window")) {
                int revision = menuReads.incrementAndGet();
                return CompletableFuture.completedFuture(
                        JsonSupport.of(
                                "ok",
                                true,
                                "message",
                                "same actual menu contents",
                                "data",
                                JsonSupport.of(
                                        "windowId",
                                        revision,
                                        "stateId",
                                        revision,
                                        "synchronizedAtMs",
                                        10000 + revision,
                                        "slots",
                                        List.of(
                                                JsonSupport.of(
                                                        "index",
                                                        0,
                                                        "item",
                                                        JsonSupport.of(
                                                                "name",
                                                                "test:part",
                                                                "count",
                                                                2))))));
            }
            if (name.equals("hold")) {
                CompletableFuture<JsonObject> f = new CompletableFuture<>();
                pending.set(f);
                token.onCancel(() -> f.completeExceptionally(new CancellationException()));
                return f;
            }
            if (name.equals("generic_action")
                    && deathActions.getAndUpdate(value -> value > 0 ? value - 1 : 0) > 0)
                return CompletableFuture.completedFuture(
                        JsonSupport.of(
                                "ok", false, "code", "player_dead", "message", "Player is dead"));
            actions.incrementAndGet();
            return CompletableFuture.completedFuture(
                    JsonSupport.of(
                            "ok",
                            true,
                            "message",
                            "actual action",
                            "data",
                            JsonSupport.of("count", actions.get())));
        }

        public void cancel() {
            CompletableFuture<JsonObject> f = pending.getAndSet(null);
            if (f != null) f.completeExceptionally(new CancellationException());
        }
    }

    @Test
    void localContinuationNeedsOnlyPlanProgramAndReviewRequestsAndKeepsProviderFile()
            throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean clocksOnly = new AtomicBoolean(), menusOnly = new AtomicBoolean();
        FakeTools tools = new FakeTools();
        HttpServer server =
                server(
                        (system, input) -> {
                            requests.incrementAndGet();
                            if (system.contains("Produce JSON"))
                                return JsonSupport.of(
                                        "goals",
                                        List.of(
                                                JsonSupport.of(
                                                        "objective",
                                                        "produce two verified parts",
                                                        "checks",
                                                        List.of(
                                                                JsonSupport.of(
                                                                        "path",
                                                                        "inventory.test:part",
                                                                        "op",
                                                                        "gte",
                                                                        "value",
                                                                        2)))));
                            if (system.contains("Return {\"program\"")) {
                                if (clocksOnly.get())
                                    return JsonSupport.of(
                                            "program",
                                            List.of(
                                                    JsonSupport.of(
                                                            "op",
                                                            "call",
                                                            "tool",
                                                            menusOnly.get()
                                                                    ? "inspect_inventory_window"
                                                                    : "observe"),
                                                    JsonSupport.of(
                                                            "op",
                                                            "return",
                                                            "value",
                                                            JsonSupport.of(
                                                                    "status",
                                                                    "continue",
                                                                    "summary",
                                                                    "same facts with later clock",
                                                                    "state",
                                                                    new JsonObject()))));
                                return JsonParser.parseString(
                                                """
                {"program":[{"op":"call","tool":"generic_action"},
                 {"op":"set","target":"n","value":{"add":[{"coalesce":[{"var":"state.n"},0]},1]}},
                 {"op":"if","condition":{"gte":[{"var":"n"},2]},"then":[{"op":"return","value":{"status":"done","summary":"two actions"}}]},
                 {"op":"return","value":{"status":"continue","summary":"first action","state":{"n":{"var":"n"}}}}]}
                """)
                                        .getAsJsonObject();
                            }
                            return JsonSupport.of(
                                    "status",
                                    "complete",
                                    "summary",
                                    "two actual parts verified",
                                    "requirements",
                                    List.of(
                                            JsonSupport.of(
                                                    "requirement",
                                                    "two parts",
                                                    "satisfied",
                                                    true,
                                                    "evidence",
                                                    "fresh matching inventory=2")));
                        });
        try {
            Path file = configuration(server);
            byte[] before = Files.readAllBytes(file);
            try (AgentController controller =
                    new AgentController(file, directory.resolve("memory"), tools, s -> {})) {
                Thread.sleep(100);
                assertEquals(0, requests.get(), "construction/tick must not call the model");
                controller.tick();
                assertEquals(0, requests.get());
                controller.submit("produce two test:part items");
                await(() -> controller.status().get("state").getAsString().equals("completed"));
                assertEquals(2, tools.actions.get());
                assertEquals(3, requests.get(), "continue must reuse the local program");
                assertArrayEquals(before, Files.readAllBytes(file));
                await(() -> controller.status().has("lastResult"));
                JsonObject completed = controller.status();
                assertEquals(3, completed.get("llmRequests").getAsInt());
                assertEquals(2, completed.get("localSegments").getAsInt());
                assertEquals(tools.dispatches.get(), completed.get("toolCalls").getAsInt());
                assertEquals(
                        "succeeded",
                        completed.getAsJsonObject("lastResult").get("outcome").getAsString());
                clocksOnly.set(true);
                controller.submit("perform meaningful new work, do not just observe clocks");
                try {
                    await(() -> controller.status().get("state").getAsString().equals("failed"));
                } catch (AssertionError ex) {
                    throw new AssertionError(
                            "Actual status: "
                                    + controller.status()
                                    + "; requests="
                                    + requests.get(),
                            ex);
                }
                assertEquals(
                        5,
                        requests.get(),
                        "clock passage, idle jitter and familiar mob motion must not keep continuation alive");
                assertEquals(2, tools.actions.get());
                menusOnly.set(true);
                controller.submit("do not spin on unchanged menu contents");
                await(() -> controller.status().get("state").getAsString().equals("failed"));
                assertEquals(7, requests.get());
                assertEquals(
                        3,
                        tools.menuReads.get(),
                        "one new menu observation followed by two repeated observations must stop despite new handles/timestamps");
                assertEquals(3, controller.status().get("localSegments").getAsInt());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stopDrainsOldActionAndProjectResumesSameFirstStageWithoutLosingBacklog() throws Exception {
        FakeTools tools = new FakeTools();
        AtomicBoolean resume = new AtomicBoolean(), confirmFullProject = new AtomicBoolean();
        AtomicReference<JsonObject> originBeforeFirstPlan = new AtomicReference<>();
        AtomicInteger intermediateReviews = new AtomicInteger(), fullReviews = new AtomicInteger();
        HttpServer server =
                server(
                        (system, input) -> {
                            if (system.contains("Produce JSON")) {
                                if (!resume.get())
                                    try {
                                        originBeforeFirstPlan.set(
                                                JsonSupport.readObject(
                                                                directory.resolve(
                                                                        "memory/agent-memory.json"),
                                                                1_000_000)
                                                        .getAsJsonObject("commandOrigin")
                                                        .deepCopy());
                                    } catch (Exception ex) {
                                        throw new IllegalStateException(
                                                "Origin must already be persisted before first plan",
                                                ex);
                                    }
                                JsonObject plan =
                                        JsonSupport.of(
                                                "goals",
                                                List.of(
                                                        JsonSupport.of(
                                                                "objective",
                                                                "accepted first stage")));
                                if (resume.get())
                                    plan.add(
                                            "project",
                                            JsonSupport.of(
                                                    "id",
                                                    input.getAsJsonArray("knownProjects")
                                                            .get(0)
                                                            .getAsJsonObject()
                                                            .get("id")
                                                            .getAsString()));
                                else
                                    plan.add(
                                            "project",
                                            JsonSupport.of(
                                                    "objective",
                                                    "progressive generic objective",
                                                    "currentStage",
                                                    "first exact stage",
                                                    "remaining",
                                                    List.of("second exact stage")));
                                return plan;
                            }
                            if (system.contains("Return {\"program\""))
                                return JsonSupport.of(
                                        "program",
                                        List.of(
                                                JsonSupport.of(
                                                        "op",
                                                        "call",
                                                        "tool",
                                                        resume.get() ? "generic_action" : "hold"),
                                                JsonSupport.of(
                                                        "op",
                                                        "return",
                                                        "value",
                                                        JsonSupport.of(
                                                                "status",
                                                                "done",
                                                                "summary",
                                                                "candidate stage"))));
                            boolean full =
                                    JsonSupport.bool(input, "fullProjectCompletionRequired", false);
                            if (full) fullReviews.incrementAndGet();
                            else intermediateReviews.incrementAndGet();
                            return JsonSupport.of(
                                    "status",
                                    "complete",
                                    "summary",
                                    "actual requested scope accepted",
                                    "projectObjectiveReviewed",
                                    full && confirmFullProject.get(),
                                    "requirements",
                                    List.of(
                                            JsonSupport.of(
                                                    "requirement",
                                                    full
                                                            ? "progressive generic objective"
                                                            : "first exact stage",
                                                    "satisfied",
                                                    true,
                                                    "evidence",
                                                    "actual tool receipt and current site inspection")));
                        });
        try {
            Path file = configuration(server), mem = directory.resolve("memory");
            try (AgentController controller = new AgentController(file, mem, tools, s -> {})) {
                controller.submit("allow progressive delivery");
                await(() -> tools.pending.get() != null);
                controller.stop();
                await(() -> controller.status().get("state").getAsString().equals("stopped"));
                assertEquals(0, tools.actions.get());
                JsonObject saved =
                        JsonSupport.readObject(mem.resolve("agent-memory.json"), 1_000_000);
                String id =
                        saved.getAsJsonArray("projects")
                                .get(0)
                                .getAsJsonObject()
                                .get("id")
                                .getAsString();
                assertEquals(
                        2,
                        saved.getAsJsonArray("projects")
                                .get(0)
                                .getAsJsonObject()
                                .getAsJsonArray("tasks")
                                .size());
                assertNotNull(originBeforeFirstPlan.get());
                assertEquals(
                        "allow progressive delivery",
                        originBeforeFirstPlan.get().get("instruction").getAsString());
                assertEquals(
                        originBeforeFirstPlan.get(),
                        saved.getAsJsonArray("projects")
                                .get(0)
                                .getAsJsonObject()
                                .get("commandOrigin"));
                assertEquals(
                        "cancelled",
                        controller
                                .status()
                                .getAsJsonObject("lastResult")
                                .get("outcome")
                                .getAsString());
                assertEquals(2, controller.status().get("llmRequests").getAsInt());
                assertEquals(1, controller.status().get("localSegments").getAsInt());
                resume.set(true);
                controller.submit("resume the existing project");
                await(() -> controller.status().get("state").getAsString().equals("completed"));
                JsonObject project =
                        JsonSupport.readObject(mem.resolve("agent-memory.json"), 1_000_000)
                                .getAsJsonArray("projects")
                                .get(0)
                                .getAsJsonObject();
                assertEquals(id, project.get("id").getAsString());
                assertEquals(
                        "second exact stage", project.getAsJsonArray("tasks").get(0).getAsString());
                assertEquals(1, tools.actions.get());
                assertEquals(originBeforeFirstPlan.get(), project.get("commandOrigin"));
                assertEquals(1, intermediateReviews.get());
                assertEquals(
                        0,
                        fullReviews.get(),
                        "intermediate stage must not require final project review");
                controller.submit("finish the remaining stage");
                await(() -> controller.status().get("state").getAsString().equals("failed"));
                JsonObject afterRejected =
                        JsonSupport.readObject(mem.resolve("agent-memory.json"), 1_000_000)
                                .getAsJsonArray("projects")
                                .get(0)
                                .getAsJsonObject();
                assertEquals(
                        1,
                        afterRejected.getAsJsonArray("tasks").size(),
                        "stage-only complete must not erase the last task");
                assertEquals(1, fullReviews.get());
                confirmFullProject.set(true);
                controller.submit("finish and verify the full original project");
                await(() -> controller.status().get("state").getAsString().equals("completed"));
                JsonObject finished =
                        JsonSupport.readObject(mem.resolve("agent-memory.json"), 1_000_000)
                                .getAsJsonArray("projects")
                                .get(0)
                                .getAsJsonObject();
                assertTrue(finished.getAsJsonArray("tasks").isEmpty());
                assertEquals(id, finished.get("id").getAsString());
                assertEquals(originBeforeFirstPlan.get(), finished.get("commandOrigin"));
                assertEquals(2, fullReviews.get());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void threeProviderPayloadsPreserveExplicitReasoningWithoutGuessingModels() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext(
                "/v1/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    requests.incrementAndGet();
                    String content = "{\"verified\":true}";
                    JsonObject envelope =
                            switch (exchange.getRequestURI().getPath()) {
                                case "/v1/responses" ->
                                        JsonSupport.of(
                                                "output",
                                                List.of(
                                                        JsonSupport.of(
                                                                "type",
                                                                "message",
                                                                "content",
                                                                List.of(
                                                                        JsonSupport.of(
                                                                                "type",
                                                                                "output_text",
                                                                                "text",
                                                                                content)))));
                                case "/v1/messages" ->
                                        JsonSupport.of(
                                                "content",
                                                List.of(
                                                        JsonSupport.of(
                                                                "type", "text", "text", content)));
                                default ->
                                        JsonSupport.of(
                                                "choices",
                                                List.of(
                                                        JsonSupport.of(
                                                                "message",
                                                                JsonSupport.of(
                                                                        "content", content))));
                            };
                    byte[] response = envelope.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            for (String type : List.of("openai-chat", "openai-responses", "anthropic")) {
                BotConfig.Provider p =
                        new BotConfig.Provider(
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                                "",
                                "user-chosen-model",
                                type,
                                "low",
                                "auto",
                                120000);
                try (LlmClient llm = new LlmClient(p, s -> {});
                        CancellationToken token = new CancellationToken(10000)) {
                    JsonObject body = llm.requestBody("system", new JsonObject());
                    assertEquals("user-chosen-model", body.get("model").getAsString());
                    if (type.equals("openai-chat"))
                        assertEquals("low", body.get("reasoning_effort").getAsString());
                    if (type.equals("openai-responses"))
                        assertEquals(
                                "json_schema",
                                body.getAsJsonObject("text")
                                        .getAsJsonObject("format")
                                        .get("type")
                                        .getAsString());
                    if (type.equals("anthropic"))
                        assertEquals(
                                2048,
                                body.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
                    assertTrue(
                            llm.complete("system", new JsonObject(), token)
                                    .get("verified")
                                    .getAsBoolean());
                }
            }
            assertEquals(3, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deathRespawnContinuesTheSameGoal() throws Exception {
        FakeTools tools = new FakeTools();
        tools.deathActions.set(1);
        AtomicInteger programs = new AtomicInteger();
        AtomicBoolean resumedContext = new AtomicBoolean();
        HttpServer server =
                server(
                        (system, input) -> {
                            if (system.contains("Produce JSON"))
                                return JsonSupport.of(
                                        "goals",
                                        List.of(
                                                JsonSupport.of(
                                                        "objective",
                                                        "produce two verified parts",
                                                        "checks",
                                                        List.of(
                                                                JsonSupport.of(
                                                                        "path",
                                                                        "inventory.test:part",
                                                                        "op",
                                                                        "gte",
                                                                        "value",
                                                                        2)))));
                            if (system.contains("Return {\"program\"")) {
                                programs.incrementAndGet();
                                if (input.getAsJsonObject("hostSurvival")
                                                .get("deathResumes")
                                                .getAsInt()
                                        >= 1) resumedContext.set(true);
                                assertTrue(
                                        input.getAsJsonObject("hostSurvival")
                                                .get("autoResumeAfterDeath")
                                                .getAsBoolean());
                                return JsonParser.parseString(
                                                """
                        {"program":[{"op":"call","tool":"generic_action"},
                         {"op":"call","tool":"generic_action"},
                         {"op":"return","value":{"status":"done","summary":"two actions"}}]}
                        """)
                                        .getAsJsonObject();
                            }
                            return JsonSupport.of(
                                    "status",
                                    "complete",
                                    "summary",
                                    "parts remain after respawn",
                                    "requirements",
                                    List.of(
                                            JsonSupport.of(
                                                    "requirement",
                                                    "two parts",
                                                    "satisfied",
                                                    true,
                                                    "evidence",
                                                    "inventory after the same goal resumed")));
                        });
        try (AgentController controller =
                new AgentController(
                        providerConfig(server, null, null),
                        directory.resolve("memory"),
                        tools,
                        s -> {})) {
            assertTrue(controller.survival().autoResumeAfterDeath());
            assertTrue(controller.survival().hostThreatPolicy());
            controller.submit("produce two test:part items after a death");
            await(() -> controller.status().get("state").getAsString().equals("completed"));
            JsonObject status = controller.status();
            assertEquals(
                    "succeeded", status.getAsJsonObject("lastResult").get("outcome").getAsString());
            assertEquals(1, status.get("deathResumes").getAsInt());
            assertEquals(2, tools.actions.get());
            assertEquals(2, programs.get());
            assertTrue(resumedContext.get(), "the same goal must see the death resume count");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabledAutoResumeEndsTheEpisode() throws Exception {
        FakeTools tools = new FakeTools();
        tools.deathActions.set(1);
        HttpServer server =
                server(
                        (system, input) -> {
                            if (system.contains("Produce JSON"))
                                return JsonSupport.of(
                                        "goals",
                                        List.of(JsonSupport.of("objective", "keep working")));
                            if (system.contains("Return {\"program\""))
                                return JsonParser.parseString(
                                                """
                        {"program":[{"op":"call","tool":"generic_action"},
                         {"op":"return","value":{"status":"done","summary":"should not finish"}}]}
                        """)
                                        .getAsJsonObject();
                            return JsonSupport.of(
                                    "status", "incomplete", "summary", "not reviewed");
                        });
        try (AgentController controller =
                new AgentController(
                        providerConfig(server, false, null),
                        directory.resolve("memory"),
                        tools,
                        s -> {})) {
            assertFalse(controller.survival().autoResumeAfterDeath());
            controller.submit("do not resume after death");
            await(() -> controller.status().get("state").getAsString().equals("failed"));
            JsonObject status = controller.status();
            assertEquals(
                    "failed", status.getAsJsonObject("lastResult").get("outcome").getAsString());
            assertTrue(
                    status.getAsJsonObject("lastResult")
                            .get("summary")
                            .getAsString()
                            .contains("autoResumeAfterDeath"));
            assertEquals(0, status.get("deathResumes").getAsInt());
            assertEquals(0, tools.actions.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void repeatedDeathHonorsResumeLimit() throws Exception {
        FakeTools tools = new FakeTools();
        tools.deathActions.set(5);
        HttpServer server =
                server(
                        (system, input) -> {
                            if (system.contains("Produce JSON"))
                                return JsonSupport.of(
                                        "goals", List.of(JsonSupport.of("objective", "survive")));
                            return JsonParser.parseString(
                                            """
                    {"program":[{"op":"call","tool":"generic_action"},
                     {"op":"return","value":{"status":"done","summary":"action"}}]}
                    """)
                                    .getAsJsonObject();
                        });
        try (AgentController controller =
                new AgentController(
                        providerConfig(server, true, 1),
                        directory.resolve("memory"),
                        tools,
                        s -> {})) {
            controller.submit("stop after the configured number of deaths");
            await(() -> controller.status().get("state").getAsString().equals("failed"));
            JsonObject status = controller.status();
            assertEquals(
                    "failed", status.getAsJsonObject("lastResult").get("outcome").getAsString());
            assertTrue(
                    status.getAsJsonObject("lastResult")
                            .get("summary")
                            .getAsString()
                            .contains("Death resume limit"));
            assertEquals(2, status.get("deathResumes").getAsInt());
            assertEquals(0, tools.actions.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stopDuringRespawnStillCancels() throws Exception {
        FakeTools tools = new FakeTools();
        tools.stayDead.set(true);
        try (AgentController controller =
                new AgentController(
                        providerConfig(null, true, null),
                        directory.resolve("memory"),
                        tools,
                        s -> {})) {
            controller.submit("respawn and continue");
            await(() -> controller.status().get("state").getAsString().equals("respawning"));
            controller.stop();
            await(
                    () -> {
                        JsonObject status = controller.status();
                        if (!status.has("lastResult")) return false;
                        String state = status.get("state").getAsString();
                        String outcome =
                                status.getAsJsonObject("lastResult").get("outcome").getAsString();
                        return outcome.equals("cancelled")
                                && (state.equals("stopped") || state.equals("cancelled"));
                    });
            assertEquals(0, tools.actions.get());
        }
    }

    private interface Handler {
        JsonObject respond(String system, JsonObject input);
    }

    private HttpServer server(Handler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    try {
                        JsonObject body =
                                JsonParser.parseString(
                                                new String(
                                                        exchange.getRequestBody().readAllBytes(),
                                                        StandardCharsets.UTF_8))
                                        .getAsJsonObject();
                        JsonArray messages = body.getAsJsonArray("messages");
                        JsonObject answer =
                                handler.respond(
                                        messages.get(0)
                                                .getAsJsonObject()
                                                .get("content")
                                                .getAsString(),
                                        JsonParser.parseString(
                                                        messages.get(1)
                                                                .getAsJsonObject()
                                                                .get("content")
                                                                .getAsString())
                                                .getAsJsonObject());
                        byte[] response =
                                JsonSupport.of(
                                                "choices",
                                                List.of(
                                                        JsonSupport.of(
                                                                "message",
                                                                JsonSupport.of(
                                                                        "content",
                                                                        answer.toString()))))
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, response.length);
                        exchange.getResponseBody().write(response);
                    } catch (Exception e) {
                        exchange.sendResponseHeaders(500, -1);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        return server;
    }

    private Path providerConfig(HttpServer server, Boolean autoResume, Integer maxDeaths)
            throws Exception {
        JsonObject root =
                JsonSupport.of(
                        "provider",
                        JsonSupport.of(
                                "baseUrl",
                                server == null
                                        ? "http://127.0.0.1:9/v1"
                                        : "http://127.0.0.1:"
                                                + server.getAddress().getPort()
                                                + "/v1",
                                "apiKey",
                                "",
                                "model",
                                "fixture-model",
                                "apiType",
                                "openai-chat"));
        if (autoResume != null || maxDeaths != null) {
            JsonObject survival = new JsonObject();
            if (autoResume != null) survival.addProperty("autoResumeAfterDeath", autoResume);
            if (maxDeaths != null) survival.addProperty("maxDeathResumes", maxDeaths);
            root.add("survival", survival);
        }
        Path file = directory.resolve("survival-" + System.nanoTime() + ".json");
        JsonSupport.writeAtomic(file, root);
        return file;
    }

    private Path configuration(HttpServer server) throws Exception {
        Path file = directory.resolve("config.json");
        JsonSupport.writeAtomic(
                file,
                JsonSupport.of(
                        "provider",
                        JsonSupport.of(
                                "baseUrl",
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                                "apiKey",
                                "",
                                "model",
                                "fixture-model",
                                "apiType",
                                "openai-chat",
                                "reasoningEffort",
                                "auto",
                                "thinkingMode",
                                "auto",
                                "timeoutMs",
                                120000),
                        "agent",
                        JsonSupport.of("maxToolCallsPerSegment", 1, "maxReplans", 0),
                        "customUserField",
                        "preserve me"));
        return file;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for controller state");
            Thread.sleep(10);
        }
    }
}
