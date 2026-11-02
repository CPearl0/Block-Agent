package io.github.cpearl0.blockagent.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.cpearl0.blockagent.core.CancellationToken;
import io.github.cpearl0.blockagent.core.ToolExecutor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Opt-in development transport. Normal installations have no listener or bridge dependency. */
public final class LocalBridge implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicReference<CancellationToken> active = new AtomicReference<>();
    private final ToolExecutor tools;
    private final Supplier<JsonObject> status;
    private final Consumer<String> command;
    private final String credential;
    private final Path descriptor;

    public static LocalBridge startIfEnabled(
            ToolExecutor tools,
            Supplier<JsonObject> status,
            Consumer<String> command,
            Path directory)
            throws IOException {
        String value = System.getProperty("blockagent.bridge.port");
        if (value == null) return null;
        int port = Integer.parseInt(value);
        if (port < 1024 || port > 65535)
            throw new IllegalArgumentException("Invalid development bridge port");
        return new LocalBridge(port, tools, status, command, directory);
    }

    private LocalBridge(
            int port,
            ToolExecutor tools,
            Supplier<JsonObject> status,
            Consumer<String> command,
            Path directory)
            throws IOException {
        this.tools = tools;
        this.status = status;
        this.command = command;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        credential = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        descriptor = directory.resolve("bridge.json");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 8);
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        Files.createDirectories(directory);
        JsonObject info = new JsonObject();
        info.addProperty("url", "http://127.0.0.1:" + port);
        info.addProperty("token", credential);
        info.addProperty("pid", ProcessHandle.current().pid());
        Files.writeString(descriptor, info.toString(), StandardCharsets.UTF_8);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null
                    || !MessageDigest.isEqual(
                            authorization.getBytes(StandardCharsets.UTF_8),
                            ("Bearer " + credential).getBytes(StandardCharsets.UTF_8))) {
                send(
                        exchange,
                        401,
                        error("unauthorized", "Development bridge credential required"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (exchange.getRequestMethod().equals("GET")) {
                if (path.equals("/status")) {
                    send(exchange, 200, status.get());
                    return;
                }
                if (path.equals("/tools")) {
                    send(exchange, 200, tools.definitions());
                    return;
                }
                send(exchange, 404, error("not_found", "Unknown bridge endpoint"));
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                send(exchange, 405, error("method", "POST required"));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(131073);
            if (body.length > 131072) {
                send(exchange, 413, error("size", "Request exceeds 128KiB"));
                return;
            }
            JsonObject input =
                    body.length == 0
                            ? new JsonObject()
                            : JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                                    .getAsJsonObject();
            switch (path) {
                case "/cancel" -> {
                    cancelCurrent();
                    command.accept("stop");
                    send(exchange, 200, ok());
                }
                case "/command" -> {
                    cancelCurrent();
                    String instruction = input.get("instruction").getAsString();
                    if (instruction.isBlank() || instruction.length() > 10000)
                        throw new IllegalArgumentException("Invalid instruction");
                    command.accept(instruction);
                    send(exchange, 202, ok());
                }
                case "/tool" -> tool(exchange, input);
                default -> send(exchange, 404, error("not_found", "Unknown bridge endpoint"));
            }
        } catch (Exception ex) {
            // Do not echo arbitrary provider/config exceptions or request content.
            try {
                send(exchange, 400, error("bridge_request_failed", ex.getClass().getSimpleName()));
            } catch (IOException ignored) {
            }
        } finally {
            exchange.close();
        }
    }

    private void tool(HttpExchange exchange, JsonObject input) throws Exception {
        JsonObject agent = status.get();
        if (agent.has("state")
                && !java.util.Set.of(
                                "idle",
                                "stopped",
                                "error",
                                "completed",
                                "failed",
                                "cancelled",
                                "closed")
                        .contains(agent.get("state").getAsString())) {
            send(
                    exchange,
                    409,
                    error(
                            "agent_busy",
                            "Stop the local agent before using the development executor"));
            return;
        }
        long timeout = input.has("timeoutMs") ? input.get("timeoutMs").getAsLong() : 60000;
        if (timeout < 100 || timeout > 300000)
            throw new IllegalArgumentException("Invalid action timeout");
        CancellationToken token = new CancellationToken(timeout);
        if (!active.compareAndSet(null, token)) {
            token.close();
            send(exchange, 409, error("action_busy", "Another bridge action is in progress"));
            return;
        }
        boolean finished = false;
        try (token) {
            JsonObject result =
                    token.await(
                            tools.execute(
                                    input.get("name").getAsString(),
                                    input.has("args")
                                            ? input.getAsJsonObject("args")
                                            : new JsonObject(),
                                    token));
            finished = true;
            send(exchange, 200, result);
        } finally {
            // Cancel only while this request still owns bridge execution.
            if (active.compareAndSet(token, null) && !finished) tools.cancel();
        }
    }

    public void cancelCurrent() {
        CancellationToken token = active.get();
        if (token != null) {
            token.cancel();
            tools.cancel();
        }
    }

    private static JsonObject error(String code, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("code", code);
        result.addProperty("message", message);
        return result;
    }

    private static JsonObject ok() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

    private static void send(HttpExchange exchange, int code, JsonElement result)
            throws IOException {
        byte[] data = result.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, data.length);
        exchange.getResponseBody().write(data);
    }

    @Override
    public void close() {
        cancelCurrent();
        server.stop(0);
        workers.shutdownNow();
        try {
            Files.deleteIfExists(descriptor);
        } catch (IOException ignored) {
        }
    }
}
