package io.github.cpearl0.blockagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LlmClientTest {
    private static final byte[] RESPONSE =
            "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);

    private LlmClient client(HttpServer server, long timeout) {
        return new LlmClient(
                new BotConfig.Provider(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "",
                        "fixture",
                        "openai-chat",
                        "auto",
                        "auto",
                        timeout),
                ignored -> {});
    }

    @Test
    void malformedOutputRetriesBeforeReturningAnyExecutableDecision() throws Exception {
        assertInvalidOutputRecovery("{broken");
        assertInvalidOutputRecovery("[]");
        assertInvalidOutputRecovery(
                JsonSupport.of(
                                "choices",
                                java.util.List.of(
                                        JsonSupport.of(
                                                "message", JsonSupport.of("content", "{invalid"))))
                        .toString());
        assertInvalidOutputRecovery("{\"choices\":[]}");
    }

    private void assertInvalidOutputRecovery(String invalidEnvelope) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        List<String> bodies = new CopyOnWriteArrayList<>();
        server.createContext(
                "/chat/completions",
                exchange -> {
                    bodies.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] response =
                            calls.incrementAndGet() == 1
                                    ? invalidEnvelope.getBytes(StandardCharsets.UTF_8)
                                    : RESPONSE;
                    try (exchange) {
                        exchange.sendResponseHeaders(200, response.length);
                        exchange.getResponseBody().write(response);
                    }
                });
        server.start();
        try (LlmClient client = client(server, 2000);
                CancellationToken command = new CancellationToken(10000)) {
            assertTrue(
                    client.complete("fixture", new JsonObject(), command).get("ok").getAsBoolean());
            assertEquals(2, calls.get());
            assertEquals(bodies.get(0), bodies.get(1));
            assertFalse(command.isCancelled());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void persistentlyMalformedModelJsonStopsAtSharedAttemptLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        byte[] malformed =
                JsonSupport.of(
                                "choices",
                                java.util.List.of(
                                        JsonSupport.of(
                                                "message", JsonSupport.of("content", "{invalid"))))
                        .toString()
                        .getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    calls.incrementAndGet();
                    try (exchange) {
                        exchange.getRequestBody().readAllBytes();
                        exchange.sendResponseHeaders(200, malformed.length);
                        exchange.getResponseBody().write(malformed);
                    }
                });
        server.start();
        try (LlmClient client = client(server, 2000);
                CancellationToken command = new CancellationToken(10000)) {
            assertTrue(
                    assertThrows(
                                    java.io.IOException.class,
                                    () -> client.complete("fixture", new JsonObject(), command))
                            .getMessage()
                            .contains("4 attempts after invalid JSON"));
            assertEquals(4, calls.get());
            assertFalse(command.isCancelled());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stalledBodyRetriesIdenticalDecisionWithoutCancellingCommand() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        AtomicInteger calls = new AtomicInteger();
        List<String> bodies = new CopyOnWriteArrayList<>();
        server.createContext(
                "/chat/completions",
                exchange -> {
                    bodies.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    int call = calls.incrementAndGet();
                    try (exchange) {
                        exchange.sendResponseHeaders(200, RESPONSE.length);
                        if (call == 1) {
                            // Headers arrive, but body never completes within the first attempt.
                            exchange.getResponseBody().write(RESPONSE, 0, 1);
                            exchange.getResponseBody().flush();
                            try {
                                Thread.sleep(900);
                            } catch (InterruptedException ignored) {
                                return;
                            }
                        } else exchange.getResponseBody().write(RESPONSE);
                    }
                });
        server.start();
        try (LlmClient client = client(server, 500);
                CancellationToken command = new CancellationToken(5000)) {
            assertTrue(
                    client.complete("fixture", new JsonObject(), command).get("ok").getAsBoolean());
            assertFalse(command.isCancelled());
            assertEquals(2, calls.get());
            assertEquals(bodies.get(0), bodies.get(1));
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void transportAttemptsAreBounded() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        AtomicInteger calls = new AtomicInteger();
        server.createContext(
                "/chat/completions",
                exchange -> {
                    calls.incrementAndGet();
                    try (exchange) {
                        exchange.getRequestBody().readAllBytes();
                        exchange.sendResponseHeaders(200, RESPONSE.length);
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ignored) {
                        }
                    }
                });
        server.start();
        try (LlmClient client = client(server, 150);
                CancellationToken command = new CancellationToken(5000)) {
            var error =
                    assertThrows(
                            java.io.IOException.class,
                            () -> client.complete("fixture", new JsonObject(), command));
            assertTrue(error.getMessage().contains("4 transport attempts"));
            assertEquals(4, calls.get());
            assertFalse(command.isCancelled());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void droppedConnectionThenStalledBodiesCannotFreezeGenerating() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        AtomicInteger calls = new AtomicInteger();
        server.createContext(
                "/chat/completions",
                exchange -> {
                    int call = calls.incrementAndGet();
                    try (exchange) {
                        exchange.getRequestBody().readAllBytes();
                        if (call == 1)
                            return; // IOException before headers, then body stalls on retries.
                        exchange.sendResponseHeaders(200, RESPONSE.length);
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ignored) {
                        }
                    }
                });
        server.start();
        long started = System.nanoTime();
        try (LlmClient client = client(server, 200);
                CancellationToken command = new CancellationToken(10000)) {
            assertThrows(
                    java.io.IOException.class,
                    () -> client.complete("fixture", new JsonObject(), command));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 4000);
            assertTrue(calls.get() >= 2);
            assertTrue(calls.get() <= 4);
            assertFalse(command.isCancelled());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void commandCancellationAbortsStalledResponseWithoutRetry() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    calls.incrementAndGet();
                    try (exchange) {
                        exchange.getRequestBody().readAllBytes();
                        exchange.sendResponseHeaders(200, RESPONSE.length);
                        entered.countDown();
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                    }
                });
        server.start();
        try (LlmClient client = client(server, 2000);
                CancellationToken command = new CancellationToken(5000)) {
            var future =
                    java.util.concurrent.CompletableFuture.runAsync(
                            () -> {
                                assertThrows(
                                        java.util.concurrent.CancellationException.class,
                                        () ->
                                                client.complete(
                                                        "fixture", new JsonObject(), command));
                            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            command.cancel();
            future.get(2, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpAuthenticationErrorIsNotRetried() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext(
                "/chat/completions",
                exchange -> {
                    calls.incrementAndGet();
                    try (exchange) {
                        exchange.getRequestBody().readAllBytes();
                        exchange.sendResponseHeaders(401, -1);
                    }
                });
        server.start();
        try (LlmClient client = client(server, 1000);
                CancellationToken command = new CancellationToken(5000)) {
            assertTrue(
                    assertThrows(
                                    java.io.IOException.class,
                                    () -> client.complete("fixture", new JsonObject(), command))
                            .getMessage()
                            .contains("401"));
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }
}
