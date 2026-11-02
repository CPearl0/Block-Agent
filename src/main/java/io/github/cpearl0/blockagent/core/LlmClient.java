package io.github.cpearl0.blockagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** No model fallback, reasoning downgrade, implicit retry, or API calls at construction. */
public final class LlmClient implements AutoCloseable {
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final BotConfig.Provider provider;
    private final Consumer<String> metrics;

    public LlmClient(BotConfig.Provider provider, Consumer<String> metrics) {
        this.provider = provider;
        this.metrics = metrics;
    }

    public JsonObject complete(String system, JsonObject input, CancellationToken command)
            throws Exception {
        command.throwIfCancelled();
        provider.requireConfigured();
        String key = provider.resolvedApiKey();
        String endpoint =
                switch (provider.apiType()) {
                    case "openai-responses" -> "responses";
                    case "anthropic" -> "messages";
                    default -> "chat/completions";
                };
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(
                                URI.create(
                                        provider.baseUrl().replaceAll("/+$", "") + "/" + endpoint))
                        .timeout(
                                Duration.ofMillis(
                                        Math.min(command.remainingMillis(), provider.timeoutMs())))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        requestBody(system, input).toString(),
                                        StandardCharsets.UTF_8));
        if (provider.apiType().equals("anthropic")) {
            builder.header("anthropic-version", "2023-06-01");
            if (!key.isBlank()) builder.header("x-api-key", key);
        } else if (!key.isBlank()) builder.header("Authorization", "Bearer " + key);
        long started = System.nanoTime();
        try (CancellationToken request = new CancellationToken(command, provider.timeoutMs())) {
            CompletableFuture<HttpResponse<InputStream>> pending =
                    http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (AutoCloseable registration = request.onCancel(() -> pending.cancel(true))) {
                HttpResponse<InputStream> response = request.await(pending);
                try (InputStream stream = response.body();
                        AutoCloseable closeBody =
                                request.onCancel(
                                        () -> {
                                            try {
                                                stream.close();
                                            } catch (IOException ignored) {
                                            }
                                        })) {
                    byte[] bytes = stream.readNBytes(4 * 1024 * 1024 + 1);
                    request.throwIfCancelled();
                    if (bytes.length > 4 * 1024 * 1024)
                        throw new IOException("LLM response exceeded 4 MiB");
                    if (response.statusCode() < 200 || response.statusCode() >= 300)
                        throw new IOException(
                                "LLM HTTP "
                                        + response.statusCode()
                                        + " ("
                                        + provider.apiType()
                                        + ", model "
                                        + provider.model()
                                        + "); inspect provider settings/service");
                    JsonObject envelope =
                            JsonSupport.object(
                                    JsonParser.parseString(
                                            new String(bytes, StandardCharsets.UTF_8)),
                                    "LLM response");
                    String text = extract(envelope);
                    JsonObject telemetry =
                            JsonSupport.of(
                                    "apiType",
                                    provider.apiType(),
                                    "model",
                                    provider.model(),
                                    "latencyMs",
                                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                    JsonObject usage = compactUsage(envelope);
                    if (!usage.isEmpty()) telemetry.add("usage", usage);
                    try {
                        metrics.accept("LLM metrics: " + telemetry);
                    } catch (RuntimeException ignored) {
                    }
                    return parseObject(text);
                }
            } catch (CancellationException ex) {
                command.throwIfCancelled();
                throw new IOException(
                        "LLM API exceeded provider.timeoutMs=" + provider.timeoutMs(), ex);
            }
        }
    }

    public JsonObject requestBody(String system, JsonObject input) {
        String user = input.toString();
        JsonArray messages = new JsonArray();
        messages.add(JsonSupport.of("role", "user", "content", user));
        String effort = provider.reasoningEffort();
        if (provider.apiType().equals("openai-responses")) {
            JsonObject result =
                    JsonSupport.of(
                            "model",
                            provider.model(),
                            "instructions",
                            system + "\nReturn one valid JSON object only.",
                            "input",
                            messages,
                            "text",
                            JsonSupport.of(
                                    "format",
                                    JsonSupport.of(
                                            "type",
                                            "json_schema",
                                            "name",
                                            "response",
                                            "schema",
                                            JsonSupport.of(
                                                    "type", "object", "additionalProperties", true),
                                            "strict",
                                            false)));
            if (!effort.equals("auto")) result.add("reasoning", JsonSupport.of("effort", effort));
            return result;
        }
        if (provider.apiType().equals("anthropic")) {
            int budget =
                    switch (effort) {
                        case "minimal" -> 1024;
                        case "low" -> 2048;
                        case "medium" -> 4096;
                        case "high" -> 8192;
                        default -> 0;
                    };
            JsonObject result =
                    JsonSupport.of(
                            "model",
                            provider.model(),
                            "max_tokens",
                            Math.max(4096, budget + 2048),
                            "system",
                            system + "\nReturn one valid JSON object only.",
                            "messages",
                            messages);
            if (budget > 0)
                result.add("thinking", JsonSupport.of("type", "enabled", "budget_tokens", budget));
            return result;
        }
        JsonArray chat = new JsonArray();
        chat.add(JsonSupport.of("role", "system", "content", system + "\nReturn JSON only."));
        chat.addAll(messages);
        JsonObject result =
                JsonSupport.of(
                        "model",
                        provider.model(),
                        "messages",
                        chat,
                        "response_format",
                        JsonSupport.of("type", "json_object"));
        if (!effort.equals("auto")) result.addProperty("reasoning_effort", effort);
        if (!provider.thinkingMode().equals("auto"))
            result.add("thinking", JsonSupport.of("type", provider.thinkingMode()));
        return result;
    }

    private JsonObject compactUsage(JsonObject envelope) {
        JsonObject result = new JsonObject();
        JsonElement value = envelope.get("usage");
        if (value == null || !value.isJsonObject()) return result;
        JsonObject usage = value.getAsJsonObject();
        // Keep native token names: Anthropic input_tokens excludes its separately reported cache
        // counts.
        boolean chat = provider.apiType().equals("openai-chat");
        copyTokenCount(result, usage, chat ? "prompt_tokens" : "input_tokens");
        copyTokenCount(result, usage, chat ? "completion_tokens" : "output_tokens");
        if (provider.apiType().equals("anthropic")) {
            copyTokenCount(result, usage, "cache_read_input_tokens");
            copyTokenCount(result, usage, "cache_creation_input_tokens");
        } else {
            JsonElement details =
                    usage.get(chat ? "prompt_tokens_details" : "input_tokens_details");
            if (details != null && details.isJsonObject())
                copyTokenCount(result, details.getAsJsonObject(), "cached_tokens");
        }
        return result;
    }

    private static void copyTokenCount(JsonObject target, JsonObject source, String key) {
        JsonElement value = source.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            return;
        try {
            long count = value.getAsBigDecimal().longValueExact();
            if (count >= 0) target.addProperty(key, count);
        } catch (ArithmeticException | NumberFormatException ignored) {
        }
    }

    private String extract(JsonObject body) {
        if (provider.apiType().equals("openai-chat")) {
            JsonElement choices = body.get("choices");
            if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty())
                throw new IllegalArgumentException("LLM Chat response has no choices");
            return JsonSupport.string(
                    JsonSupport.child(choices.getAsJsonArray().get(0).getAsJsonObject(), "message"),
                    "content",
                    "");
        }
        if (body.has("output_text") && body.get("output_text").isJsonPrimitive())
            return body.get("output_text").getAsString();
        StringBuilder text = new StringBuilder();
        JsonArray parts = new JsonArray();
        if (provider.apiType().equals("anthropic")) {
            if (body.has("content") && body.get("content").isJsonArray())
                parts.addAll(body.getAsJsonArray("content"));
        } else if (body.has("output") && body.get("output").isJsonArray())
            for (JsonElement item : body.getAsJsonArray("output")) {
                if (!item.isJsonObject()) continue;
                JsonObject message = item.getAsJsonObject();
                if ("message".equals(JsonSupport.string(message, "type", ""))
                        && message.has("content")
                        && message.get("content").isJsonArray())
                    parts.addAll(message.getAsJsonArray("content"));
            }
        for (JsonElement part : parts)
            if (part.isJsonObject()) {
                JsonObject p = part.getAsJsonObject();
                String type = JsonSupport.string(p, "type", "");
                if (type.equals("text") || type.equals("output_text"))
                    text.append(JsonSupport.string(p, "text", ""));
            }
        if (text.isEmpty()) throw new IllegalArgumentException("LLM response has no text output");
        return text.toString();
    }

    private static JsonObject parseObject(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int line = text.indexOf('\n');
            if (line >= 0 && text.endsWith("```"))
                text = text.substring(line + 1, text.length() - 3).trim();
        }
        try {
            return JsonSupport.object(JsonParser.parseString(text), "LLM JSON output");
        } catch (JsonParseException | IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "LLM returned invalid JSON output; no game actions executed from this response",
                    ex);
        }
    }

    @Override
    public void close() {
        http.shutdownNow();
    }
}
