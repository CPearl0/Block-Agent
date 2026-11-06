package io.github.cpearl0.blockagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bounded transport retries preserve the decision input and the command's monotonic deadline. */
public final class LlmClient implements AutoCloseable {
    private static final int MAX_ATTEMPTS = 4;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final java.util.Set<HttpClient> active =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean();
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
        String body = requestBody(system, input).toString();
        long started = System.nanoTime();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            command.throwIfCancelled();
            HttpResponse<byte[]> response;
            try {
                response = send(body, command);
            } catch (IOException ex) {
                command.throwIfCancelled();
                if (attempt == MAX_ATTEMPTS)
                    throw new IOException(
                            "LLM exhausted " + MAX_ATTEMPTS + " transport attempts", ex);
                report(
                        "LLM transport retry: attempt="
                                + attempt
                                + "/"
                                + MAX_ATTEMPTS
                                + ", failure="
                                + ex.getClass().getSimpleName());
                continue;
            }
            // HTTP errors are configuration/service failures, not invalid-output retries.
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IOException(
                        "LLM HTTP "
                                + response.statusCode()
                                + " ("
                                + provider.apiType()
                                + ", model "
                                + provider.model()
                                + "); inspect provider settings/service");
            JsonObject envelope;
            JsonObject result;
            try {
                envelope =
                        JsonSupport.object(
                                JsonParser.parseString(
                                        new String(response.body(), StandardCharsets.UTF_8)),
                                "LLM response");
                result = parseObject(extract(envelope));
            } catch (JsonParseException | IllegalArgumentException | IllegalStateException ex) {
                command.throwIfCancelled();
                // Nothing from this response has reached the interpreter. Retry the same decision,
                // never repair/truncate JSON into a potentially different executable program.
                if (attempt == MAX_ATTEMPTS)
                    throw new IOException(
                            "LLM exhausted "
                                    + MAX_ATTEMPTS
                                    + " attempts after invalid JSON output; no game actions executed from rejected responses",
                            ex);
                report("LLM invalid-output retry: attempt=" + attempt + "/" + MAX_ATTEMPTS);
                continue;
            }
            command.throwIfCancelled();
            JsonObject telemetry =
                    JsonSupport.of(
                            "apiType",
                            provider.apiType(),
                            "model",
                            provider.model(),
                            "latencyMs",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                            "transportAttempts",
                            attempt);
            JsonObject usage = compactUsage(envelope);
            if (!usage.isEmpty()) telemetry.add("usage", usage);
            report("LLM metrics: " + telemetry);
            return result;
        }
        throw new AssertionError("Unreachable transport attempt");
    }

    private HttpResponse<byte[]> send(String body, CancellationToken command) throws Exception {
        if (closed.get()) throw new IllegalStateException("LLM client is closed");
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
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        String key = provider.resolvedApiKey();
        if (provider.apiType().equals("anthropic")) {
            builder.header("anthropic-version", "2023-06-01");
            if (!key.isBlank()) builder.header("x-api-key", key);
        } else if (!key.isBlank()) builder.header("Authorization", "Bearer " + key);
        // A fresh client prevents a failed/stale connection from poisoning the next attempt.
        // All deadlines use CancellationToken (nanoTime), including response body delivery.
        HttpClient http =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        active.add(http);
        try (CancellationToken request =
                new CancellationToken(command, Math.min(75_000, provider.timeoutMs()))) {
            if (closed.get()) throw new IllegalStateException("LLM client is closed");
            CompletableFuture<HttpResponse<byte[]>> pending =
                    http.sendAsync(builder.build(), info -> new BoundedBody());
            try (AutoCloseable registration = request.onCancel(() -> pending.cancel(true))) {
                return request.await(pending);
            } catch (CancellationException ex) {
                command.throwIfCancelled();
                throw new IOException(
                        "LLM transport attempt exceeded monotonic request deadline", ex);
            }
        } finally {
            active.remove(http);
            http.shutdownNow();
        }
    }

    private void report(String message) {
        try {
            metrics.accept(message);
        } catch (RuntimeException ignored) {
        }
    }

    /** Whole-body asynchronous delivery, with a byte ceiling enforced before allocation grows. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;

        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return body;
        }

        public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (java.nio.ByteBuffer buffer : buffers) {
                int size = buffer.remaining();
                if (size > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("LLM response exceeded 4 MiB"));
                    return;
                }
                byte[] chunk = new byte[size];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        public void onComplete() {
            body.complete(bytes.toByteArray());
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
        closed.set(true);
        active.forEach(HttpClient::shutdownNow);
    }
}
