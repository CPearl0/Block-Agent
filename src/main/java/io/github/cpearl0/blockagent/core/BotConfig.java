package io.github.cpearl0.blockagent.core;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Existing files are read-only; no migration silently changes a user's provider choices. */
public record BotConfig(
        BotConfig.Provider provider, BotConfig.Agent agent, BotConfig.Autonomy autonomy) {
    public record Provider(
            String baseUrl,
            String apiKey,
            String model,
            String apiType,
            String reasoningEffort,
            String thinkingMode,
            long timeoutMs) {
        public String resolvedApiKey() {
            Matcher m = Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*)}$").matcher(apiKey);
            if (!m.matches()) return apiKey;
            String value = System.getenv(m.group(1));
            if (value == null || value.isBlank())
                throw new IllegalArgumentException(
                        "Required API key environment variable is unset: " + m.group(1));
            return value;
        }

        public void requireConfigured() {
            if (baseUrl.isBlank() || model.isBlank())
                throw new IllegalStateException(
                        "请先在 config/blockagent-client.json 配置 provider.baseUrl 和 provider.model");
            URI uri = URI.create(baseUrl);
            if (!Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null)
                throw new IllegalArgumentException(
                        "Provider baseUrl must be an HTTP(S) base URL without credentials/query/fragment");
            if (!thinkingMode.equals("auto") && !apiType.equals("openai-chat"))
                throw new IllegalArgumentException(
                        "Explicit thinkingMode is only supported for openai-chat");
        }
    }

    public record Agent(
            long commandTimeoutMs,
            long actionTimeoutMs,
            int maxGoals,
            int maxReplans,
            int maxProgramsPerGoal,
            int maxToolCallsPerGoal,
            int maxToolCallsPerSegment,
            int maxInstructionsPerSegment,
            int maxLoopIterations,
            int maxEvidenceBytes) {}

    public record Autonomy(boolean enabled, long decisionIntervalMs, long taskTimeoutMs) {}

    public static BotConfig load(Path path) throws IOException {
        if (!Files.exists(path)) JsonSupport.writeAtomic(path, defaults());
        JsonObject root = JsonSupport.readObject(path, 128 * 1024);
        JsonObject p = JsonSupport.child(root, "provider"),
                a = JsonSupport.child(root, "agent"),
                u = JsonSupport.child(root, "autonomy");
        String type =
                option(
                        p,
                        "apiType",
                        "openai-chat",
                        Set.of("openai-chat", "openai-responses", "anthropic"));
        Provider provider =
                new Provider(
                        JsonSupport.string(p, "baseUrl", ""),
                        JsonSupport.string(p, "apiKey", ""),
                        JsonSupport.string(p, "model", ""),
                        type,
                        option(
                                p,
                                "reasoningEffort",
                                "auto",
                                Set.of("auto", "minimal", "low", "medium", "high")),
                        option(p, "thinkingMode", "auto", Set.of("auto", "enabled", "disabled")),
                        JsonSupport.number(p, "timeoutMs", 120_000, 5000, 600_000));
        Agent agent =
                new Agent(
                        JsonSupport.number(a, "commandTimeoutMs", 7_200_000, 10_000, 86_400_000),
                        JsonSupport.number(a, "actionTimeoutMs", 60_000, 1000, 300_000),
                        n(a, "maxGoals", 12, 1, 30),
                        n(a, "maxReplans", 8, 0, 30),
                        n(a, "maxProgramsPerGoal", 16, 1, 100),
                        n(a, "maxToolCallsPerGoal", 400, 1, 4000),
                        n(a, "maxToolCallsPerSegment", 100, 1, 500),
                        n(a, "maxInstructionsPerSegment", 20_000, 100, 100_000),
                        n(a, "maxLoopIterations", 4096, 1, 20_000),
                        n(a, "maxEvidenceBytes", 131072, 8192, 262144));
        return new BotConfig(
                provider,
                agent,
                new Autonomy(
                        JsonSupport.bool(u, "enabled", false),
                        JsonSupport.number(u, "decisionIntervalMs", 60_000, 10_000, 3_600_000),
                        JsonSupport.number(u, "taskTimeoutMs", 600_000, 30_000, 3_600_000)));
    }

    private static int n(JsonObject o, String k, int d, int min, int max) {
        return (int) JsonSupport.number(o, k, d, min, max);
    }

    private static String option(JsonObject p, String k, String d, Set<String> allowed) {
        String value = JsonSupport.string(p, k, d);
        if (!allowed.contains(value))
            throw new IllegalArgumentException("Unsupported " + k + ": " + value);
        return value;
    }

    private static JsonObject defaults() {
        return JsonSupport.of(
                "provider",
                JsonSupport.of(
                        "baseUrl",
                        "",
                        "apiKey",
                        "",
                        "model",
                        "",
                        "apiType",
                        "openai-chat",
                        "reasoningEffort",
                        "auto",
                        "thinkingMode",
                        "auto",
                        "timeoutMs",
                        120000),
                "agent",
                JsonSupport.of(
                        "commandTimeoutMs",
                        7200000,
                        "actionTimeoutMs",
                        60000,
                        "maxGoals",
                        12,
                        "maxReplans",
                        8,
                        "maxProgramsPerGoal",
                        16,
                        "maxToolCallsPerGoal",
                        400,
                        "maxToolCallsPerSegment",
                        100,
                        "maxInstructionsPerSegment",
                        20000,
                        "maxLoopIterations",
                        4096,
                        "maxEvidenceBytes",
                        131072),
                "autonomy",
                JsonSupport.of(
                        "enabled", false, "decisionIntervalMs", 60000, "taskTimeoutMs", 600000));
    }
}
