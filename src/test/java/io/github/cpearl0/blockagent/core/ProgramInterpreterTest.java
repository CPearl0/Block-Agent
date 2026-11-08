package io.github.cpearl0.blockagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProgramInterpreterTest {
    private static final BotConfig.Agent LIMITS =
            new BotConfig.Agent(10000, 1000, 12, 2, 4, 20, 5, 2000, 100, 8192, 20480);

    private static JsonArray program(String text) {
        return JsonParser.parseString(text).getAsJsonArray();
    }

    private static final class FakeTools implements ToolExecutor {
        final AtomicInteger calls = new AtomicInteger();
        boolean failure;
        String code = "";

        public JsonArray definitions() {
            return new JsonArray();
        }

        public CompletableFuture<JsonObject> execute(
                String name, JsonObject args, CancellationToken token) {
            token.throwIfCancelled();
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    JsonSupport.of(
                            "ok",
                            !failure,
                            "code",
                            code,
                            "message",
                            failure ? "blocked" : "accepted",
                            "data",
                            args));
        }

        public void cancel() {}
    }

    @Test
    void runsGenericLocalLoopsAndNamespacedArgumentsWithoutModelCalls() throws Exception {
        FakeTools tools = new FakeTools();
        List<JsonObject> receipts = new ArrayList<>();
        JsonArray code =
                program(
                        """
            [{"op":"set","target":"sum","value":0},
             {"op":"forEach","items":[2,3,4],"as":"n","body":[
              {"op":"call","tool":"generic_action","args":{"id":"example:part","amount":{"var":"n"}},"as":"r"},
              {"op":"set","target":"sum","value":{"add":[{"var":"sum"},{"var":"r.data.amount"}]}}]},
             {"op":"return","value":{"status":"done","summary":"verified local arithmetic","state":{"sum":{"var":"sum"}}}}]
            """);
        try (CancellationToken token = new CancellationToken(10000)) {
            JsonObject result =
                    new ProgramInterpreter(LIMITS, tools, receipts::add)
                            .run(
                                    code,
                                    new JsonObject(),
                                    new JsonObject(),
                                    new JsonObject(),
                                    token,
                                    new ProgramInterpreter.Budget());
            assertEquals(9, result.getAsJsonObject("state").get("sum").getAsInt());
            assertEquals(3, tools.calls.get());
            assertEquals(3, receipts.size());
            assertEquals(
                    "example:part",
                    receipts.getFirst().getAsJsonObject("args").get("id").getAsString());
        }
    }

    @Test
    void continuationReadsSavedCursorWithFreshSnapshot() throws Exception {
        FakeTools tools = new FakeTools();
        JsonArray code =
                program(
                        """
            [{"op":"return","value":{"status":"continue","summary":"saved cursor","state":{
              "cursor":{"add":[{"coalesce":[{"var":"state.cursor"},0]},1]},
              "y":{"var":"snapshot.y"}}}}]
            """);
        try (CancellationToken token = new CancellationToken(10000)) {
            ProgramInterpreter interpreter = new ProgramInterpreter(LIMITS, tools, r -> {});
            JsonObject first =
                    interpreter.run(
                            code,
                            JsonSupport.of("y", 10),
                            new JsonObject(),
                            new JsonObject(),
                            token,
                            new ProgramInterpreter.Budget());
            JsonObject second =
                    interpreter.run(
                            code,
                            JsonSupport.of("y", -5),
                            first.getAsJsonObject("state"),
                            new JsonObject(),
                            token,
                            new ProgramInterpreter.Budget());
            assertEquals(2, second.getAsJsonObject("state").get("cursor").getAsInt());
            assertEquals(-5, second.getAsJsonObject("state").get("y").getAsInt());
        }
    }

    @Test
    void sharedCallBudgetSurvivesFreshInterpreterSegments() throws Exception {
        FakeTools tools = new FakeTools();
        ProgramInterpreter.Budget shared = new ProgramInterpreter.Budget();
        shared.calls = 19;
        JsonArray code =
                program(
                        """
            [{"op":"call","tool":"generic_action"},{"op":"return","value":{"status":"continue","summary":"one action","state":{"cursor":1}}}]
            """);
        try (CancellationToken token = new CancellationToken(10000)) {
            new ProgramInterpreter(LIMITS, tools, r -> {})
                    .run(code, new JsonObject(), new JsonObject(), new JsonObject(), token, shared);
            assertThrows(
                    ProgramInterpreter.BudgetExceeded.class,
                    () ->
                            new ProgramInterpreter(LIMITS, tools, r -> {})
                                    .run(
                                            code,
                                            new JsonObject(),
                                            new JsonObject(),
                                            new JsonObject(),
                                            token,
                                            shared));
            assertEquals(1, tools.calls.get());
        }
    }

    @Test
    void cancellationCannotBeTurnedIntoAllowFailureAndInfiniteLoopIsBounded() throws Exception {
        FakeTools tools = new FakeTools();
        try (CancellationToken token = new CancellationToken(10000)) {
            token.cancel();
            assertThrows(
                    CancellationException.class,
                    () ->
                            new ProgramInterpreter(LIMITS, tools, r -> {})
                                    .run(
                                            program(
                                                    "[{\"op\":\"call\",\"tool\":\"generic_action\",\"allowFailure\":true}]"),
                                            new JsonObject(),
                                            new JsonObject(),
                                            new JsonObject(),
                                            token,
                                            new ProgramInterpreter.Budget()));
            assertEquals(0, tools.calls.get());
        }
        try (CancellationToken token = new CancellationToken(10000)) {
            assertThrows(
                    ProgramInterpreter.BudgetExceeded.class,
                    () ->
                            new ProgramInterpreter(LIMITS, tools, r -> {})
                                    .run(
                                            program(
                                                    "[{\"op\":\"while\",\"max\":3,\"condition\":true,\"body\":[]}]"),
                                            new JsonObject(),
                                            new JsonObject(),
                                            new JsonObject(),
                                            token,
                                            new ProgramInterpreter.Budget()));
        }
    }

    @Test
    void repeatedOrdinaryFailureStillTerminatesAndSnapshotCannotBeOverwritten() throws Exception {
        FakeTools tools = new FakeTools();
        tools.failure = true;
        try (CancellationToken token = new CancellationToken(10000)) {
            assertThrows(
                    ProgramInterpreter.BudgetExceeded.class,
                    () ->
                            new ProgramInterpreter(LIMITS, tools, r -> {})
                                    .run(
                                            program(
                                                    "[{\"op\":\"while\",\"max\":10,\"condition\":true,\"body\":[{\"op\":\"call\",\"tool\":\"generic_action\",\"allowFailure\":true}]}]"),
                                            new JsonObject(),
                                            new JsonObject(),
                                            new JsonObject(),
                                            token,
                                            new ProgramInterpreter.Budget()));
            assertEquals(3, tools.calls.get());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new ProgramInterpreter(LIMITS, tools, r -> {})
                                    .run(
                                            program(
                                                    "[{\"op\":\"set\",\"target\":\"snapshot.inventory\",\"value\":[]}]"),
                                            new JsonObject(),
                                            new JsonObject(),
                                            new JsonObject(),
                                            token,
                                            new ProgramInterpreter.Budget()));
        }
    }

    @Test
    void playerDeathEscapesAllowFailure() throws Exception {
        FakeTools tools = new FakeTools();
        tools.failure = true;
        tools.code = "player_dead";
        try (CancellationToken token = new CancellationToken(10000)) {
            ProgramInterpreter.ToolFailure failure =
                    assertThrows(
                            ProgramInterpreter.ToolFailure.class,
                            () ->
                                    new ProgramInterpreter(LIMITS, tools, receipt -> {})
                                            .run(
                                                    program(
                                                            """
                            [{"op":"call","tool":"generic_action","allowFailure":true},
                             {"op":"return","value":{"status":"done","summary":"should not run"}}]
                            """),
                                                    new JsonObject(),
                                                    new JsonObject(),
                                                    new JsonObject(),
                                                    token,
                                                    new ProgramInterpreter.Budget()));
            assertEquals("player_dead", failure.receipt.get("code").getAsString());
            assertEquals(1, tools.calls.get());
        }
    }
}
