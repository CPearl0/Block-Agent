package io.github.cpearl0.blockagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** A small JSON program language. It has no reflection, I/O, Java/JS execution or concurrency. */
public final class ProgramInterpreter {
    public static final class Budget {
        public int calls;
        final Map<String, Integer> failures = new HashMap<>();
    }

    public static final class ToolFailure extends RuntimeException {
        public final JsonObject receipt;

        ToolFailure(String name, JsonObject result) {
            super(name + ": " + JsonSupport.string(result, "message", "Tool failed"));
            receipt = result.deepCopy();
        }
    }

    public static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded(String message) {
            super(message);
        }
    }

    private enum Flow {
        NEXT,
        BREAK,
        RETURN
    }

    private static final Set<String> EXPRESSIONS =
            Set.of(
                    "var",
                    "literal",
                    "get",
                    "eq",
                    "ne",
                    "lt",
                    "lte",
                    "gt",
                    "gte",
                    "and",
                    "or",
                    "not",
                    "add",
                    "sub",
                    "mul",
                    "div",
                    "mod",
                    "len",
                    "at",
                    "contains",
                    "coalesce");
    private final BotConfig.Agent limits;
    private final ToolExecutor tools;
    private final Consumer<JsonObject> receipt;
    private JsonObject variables;
    private CancellationToken token;
    private Budget budget;
    private int instructions, calls, loopDepth, blockDepth;
    private JsonObject output;

    public ProgramInterpreter(
            BotConfig.Agent limits, ToolExecutor tools, Consumer<JsonObject> receipt) {
        this.limits = limits;
        this.tools = tools;
        this.receipt = receipt;
    }

    public JsonObject run(
            JsonArray program,
            JsonObject snapshot,
            JsonObject state,
            JsonObject command,
            CancellationToken token,
            Budget budget)
            throws Exception {
        if (JsonSupport.bytes(program) > 65_536)
            throw new IllegalArgumentException("Program exceeds 64 KiB");
        this.token = token;
        this.budget = budget;
        calls = 0;
        instructions = 0;
        loopDepth = 0;
        blockDepth = 0;
        output = null;
        variables = JsonSupport.of("snapshot", snapshot, "state", state, "command", command);
        if (block(program) != Flow.RETURN || output == null)
            throw new IllegalArgumentException(
                    "Program must return an explicit done/continue/replan result");
        token.throwIfCancelled();
        String status = JsonSupport.string(output, "status", "");
        if (!Set.of("done", "continue", "replan").contains(status))
            throw new IllegalArgumentException("Invalid program result.status");
        String summary = JsonSupport.string(output, "summary", "").trim();
        if (summary.isEmpty())
            throw new IllegalArgumentException("Program result.summary is required");
        if (summary.length() > 500)
            output.addProperty("summary", summary.substring(0, 480) + "… [truncated]");
        if (status.equals("continue") && !output.has("state"))
            throw new IllegalArgumentException("continue requires explicit JSON state");
        if (output.has("state")
                && (!output.get("state").isJsonObject()
                        || JsonSupport.bytes(output.get("state")) > 8192))
            throw new IllegalArgumentException(
                    "Program state must be an object of at most 8192 UTF-8 bytes");
        if (status.equals("replan")
                && !Set.of("goal", "plan").contains(JsonSupport.string(output, "scope", "goal")))
            throw new IllegalArgumentException("Invalid replan scope");
        return output.deepCopy();
    }

    private void step() {
        token.throwIfCancelled();
        if (++instructions > limits.maxInstructionsPerSegment())
            throw new BudgetExceeded("Program instruction budget exceeded");
    }

    private Flow block(JsonArray statements) throws Exception {
        if (++blockDepth > 64) throw new BudgetExceeded("Statement nesting exceeds 64");
        try {
            for (JsonElement element : statements) {
                step();
                JsonObject statement = JsonSupport.object(element, "Program instruction");
                String op = JsonSupport.string(statement, "op", "");
                switch (op) {
                    case "set" ->
                            put(
                                    JsonSupport.string(statement, "target", ""),
                                    eval(required(statement, "value"), 0));
                    case "call" -> call(statement);
                    case "if" -> {
                        JsonElement selected =
                                statement.get(
                                        truth(eval(required(statement, "condition"), 0))
                                                ? "then"
                                                : "else");
                        if (selected != null) {
                            Flow flow = block(array(selected, "if branch"));
                            if (flow != Flow.NEXT) return flow;
                        }
                    }
                    case "while" -> {
                        int max =
                                (int)
                                        JsonSupport.number(
                                                statement,
                                                "max",
                                                limits.maxLoopIterations(),
                                                1,
                                                limits.maxLoopIterations());
                        int iteration = 0;
                        loopDepth++;
                        try {
                            while (truth(eval(required(statement, "condition"), 0))) {
                                step();
                                if (++iteration > max)
                                    throw new BudgetExceeded("while iteration bound exceeded");
                                Flow flow = block(array(required(statement, "body"), "while body"));
                                if (flow == Flow.RETURN) return flow;
                                if (flow == Flow.BREAK) break;
                            }
                        } finally {
                            loopDepth--;
                        }
                    }
                    case "forEach" -> {
                        JsonArray values =
                                array(eval(required(statement, "items"), 0), "forEach items");
                        if (values.size() > limits.maxLoopIterations())
                            throw new BudgetExceeded("forEach iteration bound exceeded");
                        String name = JsonSupport.string(statement, "as", "item");
                        loopDepth++;
                        try {
                            for (int i = 0; i < values.size(); i++) {
                                step();
                                put(name, values.get(i));
                                if (statement.has("index"))
                                    put(
                                            JsonSupport.string(statement, "index", "index"),
                                            new JsonPrimitive(i));
                                Flow flow =
                                        block(array(required(statement, "body"), "forEach body"));
                                if (flow == Flow.RETURN) return flow;
                                if (flow == Flow.BREAK) break;
                            }
                        } finally {
                            loopDepth--;
                        }
                    }
                    case "break" -> {
                        if (loopDepth == 0)
                            throw new IllegalArgumentException("break outside loop");
                        return Flow.BREAK;
                    }
                    case "return" -> {
                        output =
                                JsonSupport.object(
                                        eval(required(statement, "value"), 0), "Program result");
                        return Flow.RETURN;
                    }
                    default -> throw new IllegalArgumentException("Unknown instruction: " + op);
                }
            }
            return Flow.NEXT;
        } finally {
            blockDepth--;
        }
    }

    private void call(JsonObject statement) throws Exception {
        if (calls >= limits.maxToolCallsPerSegment())
            throw new BudgetExceeded(
                    "Segment tool budget exhausted; return continue before this boundary");
        if (budget.calls >= limits.maxToolCallsPerGoal())
            throw new BudgetExceeded("Goal shared tool budget exhausted");
        String name = JsonSupport.string(statement, "tool", "");
        if (!name.matches("[A-Za-z0-9_.:-]{1,100}"))
            throw new IllegalArgumentException("Invalid tool identifier");
        JsonObject args =
                statement.has("args")
                        ? JsonSupport.object(eval(statement.get("args"), 0), "Tool args")
                        : new JsonObject();
        if (JsonSupport.bytes(args) > 32768)
            throw new BudgetExceeded("Tool arguments exceed 32 KiB");
        calls++;
        budget.calls++;
        CompletableFuture<JsonObject> pending = null;
        try (CancellationToken action =
                new CancellationToken(
                        token,
                        Math.min(limits.actionTimeoutMs(), Math.max(1, token.remainingMillis())))) {
            token.throwIfCancelled();
            pending = Objects.requireNonNull(tools.execute(name, args, action), "Tool future");
            JsonObject result;
            try {
                result = Objects.requireNonNull(action.await(pending), "Tool result");
            } catch (CancellationException ex) {
                token.throwIfCancelled();
                throw new IllegalStateException("Tool action timed out: " + name, ex);
            }
            token.throwIfCancelled();
            if (!result.has("ok")
                    || !result.get("ok").isJsonPrimitive()
                    || !result.getAsJsonPrimitive("ok").isBoolean())
                throw new IllegalArgumentException("Tool result requires boolean ok: " + name);
            receipt.accept(
                    JsonSupport.of(
                            "tool",
                            name,
                            "args",
                            args,
                            "result",
                            result,
                            "completedAtMs",
                            System.currentTimeMillis()));
            if (!result.get("ok").getAsBoolean()) {
                String key =
                        name
                                + args
                                + JsonSupport.string(result, "code", "")
                                + JsonSupport.string(result, "message", "");
                if (budget.failures.merge(key, 1, Integer::sum) >= 3)
                    throw new BudgetExceeded(
                            "Repeated identical tool failure; change target or strategy");
                if (!JsonSupport.bool(statement, "allowFailure", false))
                    throw new ToolFailure(name, result);
            }
            if (statement.has("as")) put(JsonSupport.string(statement, "as", "result"), result);
        } finally {
            // Cancelling an await does not stop a native mutation. Keep the serial command lane
            // occupied until the implementation has settled its actual action future.
            if (pending != null && !pending.isDone()) {
                tools.cancel();
                pending.handle((value, error) -> null).join();
            }
        }
    }

    private JsonElement eval(JsonElement expression, int depth) {
        step();
        if (depth > 64) throw new BudgetExceeded("Expression nesting exceeds 64");
        if (expression == null || expression.isJsonNull()) return JsonNull.INSTANCE;
        if (expression.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement value : expression.getAsJsonArray())
                result.add(eval(value, depth + 1));
            return result;
        }
        if (!expression.isJsonObject()) return expression.deepCopy();
        JsonObject object = expression.getAsJsonObject();
        if (object.size() != 1 || !EXPRESSIONS.contains(object.keySet().iterator().next())) {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet())
                result.add(entry.getKey(), eval(entry.getValue(), depth + 1));
            return result;
        }
        String op = object.keySet().iterator().next();
        JsonElement raw = object.get(op);
        if (op.equals("literal")) return raw.deepCopy();
        if (op.equals("var")) {
            String name = raw.getAsString();
            if (name.equals("budget") || name.startsWith("budget.")) {
                JsonObject live =
                        JsonSupport.of(
                                "remainingToolCallsForSegment",
                                limits.maxToolCallsPerSegment() - calls,
                                "remainingToolCallsForGoal",
                                limits.maxToolCallsPerGoal() - budget.calls,
                                "remainingTimeMs",
                                token.remainingMillis());
                return (name.equals("budget") ? live : JsonSupport.path(live, name.substring(7)))
                        .deepCopy();
            }
            return JsonSupport.path(variables, name).deepCopy();
        }
        if (op.equals("not")) return new JsonPrimitive(!truth(eval(raw, depth + 1)));
        if (op.equals("len")) {
            JsonElement v = eval(raw, depth + 1);
            return new JsonPrimitive(
                    v.isJsonArray()
                            ? v.getAsJsonArray().size()
                            : v.isJsonObject()
                                    ? v.getAsJsonObject().size()
                                    : v.isJsonNull() ? 0 : v.getAsString().length());
        }
        JsonArray operands = array(raw, "Expression " + op);
        if (op.equals("and") || op.equals("or")) {
            boolean and = op.equals("and");
            for (JsonElement value : operands)
                if (truth(eval(value, depth + 1)) != and) return new JsonPrimitive(!and);
            return new JsonPrimitive(and);
        }
        if (op.equals("coalesce")) {
            for (JsonElement value : operands) {
                JsonElement v = eval(value, depth + 1);
                if (!v.isJsonNull()) return v;
            }
            return JsonNull.INSTANCE;
        }
        if (operands.size() != 2)
            throw new IllegalArgumentException(op + " requires exactly two operands");
        JsonElement left = eval(operands.get(0), depth + 1),
                right = eval(operands.get(1), depth + 1);
        return switch (op) {
            case "get" -> JsonSupport.path(left, right.getAsString()).deepCopy();
            case "at" -> {
                int index = number(right).intValueExact();
                yield left.isJsonArray() && index >= 0 && index < left.getAsJsonArray().size()
                        ? left.getAsJsonArray().get(index).deepCopy()
                        : JsonNull.INSTANCE;
            }
            case "eq" -> new JsonPrimitive(left.equals(right));
            case "ne" -> new JsonPrimitive(!left.equals(right));
            case "lt" -> new JsonPrimitive(number(left).compareTo(number(right)) < 0);
            case "lte" -> new JsonPrimitive(number(left).compareTo(number(right)) <= 0);
            case "gt" -> new JsonPrimitive(number(left).compareTo(number(right)) > 0);
            case "gte" -> new JsonPrimitive(number(left).compareTo(number(right)) >= 0);
            case "add" -> new JsonPrimitive(number(left).add(number(right)));
            case "sub" -> new JsonPrimitive(number(left).subtract(number(right)));
            case "mul" -> new JsonPrimitive(number(left).multiply(number(right)));
            case "div" ->
                    new JsonPrimitive(number(left).divide(number(right), MathContext.DECIMAL64));
            case "mod" -> new JsonPrimitive(number(left).remainder(number(right)));
            case "contains" ->
                    new JsonPrimitive(
                            left.isJsonArray()
                                    ? left.getAsJsonArray().contains(right)
                                    : left.isJsonObject()
                                            ? left.getAsJsonObject().has(right.getAsString())
                                            : left.getAsString().contains(right.getAsString()));
            default -> throw new IllegalArgumentException("Unknown expression: " + op);
        };
    }

    private void put(String target, JsonElement value) {
        if (!target.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*"))
            throw new IllegalArgumentException("Invalid variable target");
        String[] path = target.split("\\.");
        if (Set.of("snapshot", "command", "budget").contains(path[0]))
            throw new IllegalArgumentException("Cannot overwrite runtime observation/context");
        JsonObject cursor = variables;
        for (int i = 0; i < path.length - 1; i++) {
            JsonElement existing = cursor.get(path[i]);
            if (existing == null) {
                JsonObject next = new JsonObject();
                cursor.add(path[i], next);
                cursor = next;
            } else cursor = JsonSupport.object(existing, "Assignment parent");
        }
        cursor.add(path[path.length - 1], value.deepCopy());
        if (JsonSupport.bytes(value) > 262_144 || JsonSupport.bytes(variables) > 1_048_576)
            throw new BudgetExceeded("Program variable memory budget exceeded");
    }

    private static BigDecimal number(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Numeric expression requires actual JSON numbers");
        BigDecimal n = value.getAsBigDecimal();
        if (n.precision() > 100 || Math.abs(n.scale()) > 100)
            throw new IllegalArgumentException("Numeric operand exceeds supported precision");
        return n;
    }

    private static boolean truth(JsonElement value) {
        if (value == null || value.isJsonNull()) return false;
        if (value.isJsonPrimitive()) {
            JsonPrimitive v = value.getAsJsonPrimitive();
            if (v.isBoolean()) return v.getAsBoolean();
            if (v.isNumber()) return number(v).signum() != 0;
            return !v.getAsString().isEmpty();
        }
        return true;
    }

    private static JsonArray array(JsonElement value, String label) {
        if (value == null || !value.isJsonArray())
            throw new IllegalArgumentException(label + " must be an array");
        return value.getAsJsonArray();
    }

    private static JsonElement required(JsonObject value, String key) {
        if (!value.has(key)) throw new IllegalArgumentException("Missing " + key);
        return value.get(key);
    }
}
