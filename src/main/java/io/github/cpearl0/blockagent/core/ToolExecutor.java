package io.github.cpearl0.blockagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/** Implementations must settle pending futures on cancellation and confirm real game results. */
public interface ToolExecutor {
    JsonArray definitions();

    CompletableFuture<JsonObject> execute(String name, JsonObject args, CancellationToken token);

    void cancel();
}
