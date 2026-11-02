package io.github.cpearl0.blockagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Small, bounded project/episode store; only the controller's serial worker mutates it. */
final class AgentMemory {
    private final Path file;
    private JsonObject root;

    AgentMemory(Path directory) throws IOException {
        file = directory.resolve("agent-memory.json");
        root =
                Files.exists(file)
                        ? JsonSupport.readObject(file, 2 * 1024 * 1024)
                        : JsonSupport.of(
                                "version",
                                1,
                                "projects",
                                new JsonArray(),
                                "episodes",
                                new JsonArray());
        if (!root.has("projects")
                || !root.get("projects").isJsonArray()
                || !root.has("episodes")
                || !root.get("episodes").isJsonArray())
            throw new IOException(
                    "Invalid agent memory; preserve file and repair it before resuming");
        if (root.getAsJsonArray("projects").size() > 32
                || root.getAsJsonArray("episodes").size() > 24)
            throw new IOException("Agent memory exceeds record limits; preserve and repair it");
        Set<String> ids = new HashSet<>();
        try {
            for (JsonElement item : root.getAsJsonArray("projects")) {
                JsonObject project = JsonSupport.object(item, "Saved project");
                String id = requiredText(project, "id");
                requiredText(project, "scope");
                requiredText(project, "objective");
                if (!ids.add(id)
                        || !project.has("tasks")
                        || !project.get("tasks").isJsonArray()
                        || project.getAsJsonArray("tasks").size() > 25)
                    throw new IllegalArgumentException("Invalid saved project tasks/id");
                for (JsonElement task : project.getAsJsonArray("tasks"))
                    if (!task.isJsonPrimitive()
                            || !task.getAsJsonPrimitive().isString()
                            || task.getAsString().isBlank()
                            || task.getAsString().length() > 4000)
                        throw new IllegalArgumentException("Invalid saved stage");
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid saved project; preserve memory and repair it", ex);
        }
    }

    JsonArray projects(String scope) {
        JsonArray result = new JsonArray();
        for (JsonElement p : root.getAsJsonArray("projects")) {
            JsonObject project = p.getAsJsonObject();
            if (scope.equals(JsonSupport.string(project, "scope", ""))
                    && !project.getAsJsonArray("tasks").isEmpty()) result.add(project.deepCopy());
        }
        return result;
    }

    JsonArray episodes() {
        return root.getAsJsonArray("episodes").deepCopy();
    }

    void beginCommand(String commandId, String command, JsonObject snapshot, long startedAtMs)
            throws IOException {
        JsonObject origin =
                JsonSupport.of(
                        "commandId",
                        commandId,
                        "instruction",
                        command,
                        "startedAtMs",
                        startedAtMs,
                        "scope",
                        "Historical initial player location for this command; not a verified building site or route");
        for (String key : List.of("serverId", "serverAddress", "server", "dimension", "position"))
            if (snapshot.has(key)) origin.add(key, snapshot.get(key).deepCopy());
        root.add("commandOrigin", origin);
        save();
    }

    JsonObject commandOrigin(String commandId) {
        JsonElement value = root.get("commandOrigin");
        return value != null
                        && value.isJsonObject()
                        && commandId.equals(
                                JsonSupport.string(value.getAsJsonObject(), "commandId", ""))
                ? value.getAsJsonObject().deepCopy()
                : new JsonObject();
    }

    JsonObject attach(JsonObject request, String scope, String commandId) throws IOException {
        String id = JsonSupport.string(request, "id", "");
        if (!id.isBlank()) {
            JsonObject existing = find(id);
            if (existing == null || !scope.equals(JsonSupport.string(existing, "scope", "")))
                throw new IllegalArgumentException("Unknown project or different server/dimension");
            existing.addProperty("lastCommandId", commandId);
            save();
            return existing.deepCopy();
        }
        String objective = requiredText(request, "objective"),
                current = requiredText(request, "currentStage");
        JsonArray tasks = new JsonArray();
        tasks.add(current);
        JsonArray remaining =
                request.has("remaining") ? request.getAsJsonArray("remaining") : new JsonArray();
        if (remaining.size() > 24)
            throw new IllegalArgumentException("Project backlog exceeds 24 stages");
        for (JsonElement item : remaining) {
            String text = item.getAsString().trim();
            if (text.isBlank() || text.length() > 1000)
                throw new IllegalArgumentException("Invalid project stage");
            if (!text.equals(current)) tasks.add(text);
        }
        JsonObject project =
                JsonSupport.of(
                        "id",
                        UUID.randomUUID().toString(),
                        "scope",
                        scope,
                        "objective",
                        objective,
                        "tasks",
                        tasks,
                        "lastCommandId",
                        commandId,
                        "createdAtMs",
                        System.currentTimeMillis());
        if (request.has("originalCommand"))
            project.addProperty(
                    "originalCommand", JsonSupport.string(request, "originalCommand", ""));
        JsonObject commandOrigin = commandOrigin(commandId);
        if (!commandOrigin.isEmpty()) project.add("commandOrigin", commandOrigin);
        if (request.has("anchor")) {
            JsonObject anchor = JsonSupport.object(request.get("anchor"), "Project anchor");
            if (anchor.size() != 3)
                throw new IllegalArgumentException("Project anchor must contain only x/y/z");
            for (String axis : List.of("x", "y", "z")) {
                if (!anchor.has(axis))
                    throw new IllegalArgumentException("Missing project anchor axis");
                JsonSupport.number(anchor, axis, 0, -30_000_000, 30_000_000);
            }
            project.add("anchor", anchor.deepCopy());
            project.addProperty(
                    "anchorScope",
                    "Model-selected intended reference; reobserve before use, not certified construction geometry");
        }
        JsonArray projects = root.getAsJsonArray("projects");
        if (projects.size() >= 32) {
            int completed = -1;
            for (int i = 0; i < projects.size(); i++)
                if (projects.get(i).getAsJsonObject().getAsJsonArray("tasks").isEmpty()) {
                    completed = i;
                    break;
                }
            if (completed < 0)
                throw new IllegalStateException(
                        "Project storage full; finish existing projects before creating more");
            projects.remove(completed);
        }
        projects.add(project);
        save();
        return project.deepCopy();
    }

    void attempt(
            String projectId, String commandId, String outcome, String summary, boolean acceptStage)
            throws IOException {
        if (projectId == null) return;
        JsonObject p = find(projectId);
        if (p == null || !commandId.equals(JsonSupport.string(p, "lastCommandId", ""))) return;
        p.add(
                "lastAttempt",
                JsonSupport.of(
                        "outcome",
                        outcome,
                        "summary",
                        summary,
                        "completedAtMs",
                        System.currentTimeMillis()));
        if (acceptStage && !p.getAsJsonArray("tasks").isEmpty())
            p.getAsJsonArray("tasks").remove(0);
        save();
    }

    void episode(String command, String outcome, String summary) throws IOException {
        JsonArray episodes = root.getAsJsonArray("episodes");
        episodes.add(
                JsonSupport.of(
                        "command",
                        clip(command, 2000),
                        "outcome",
                        outcome,
                        "summary",
                        clip(summary, 2000),
                        "completedAtMs",
                        System.currentTimeMillis()));
        while (episodes.size() > 24) episodes.remove(0);
        save();
    }

    private JsonObject find(String id) {
        for (JsonElement p : root.getAsJsonArray("projects"))
            if (id.equals(JsonSupport.string(p.getAsJsonObject(), "id", "")))
                return p.getAsJsonObject();
        return null;
    }

    private void save() throws IOException {
        JsonSupport.writeAtomic(file, root);
    }

    private static String requiredText(JsonObject o, String key) {
        String t = JsonSupport.string(o, key, "").trim();
        if (t.isEmpty() || t.length() > 4000)
            throw new IllegalArgumentException("Invalid project " + key);
        return t;
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "… [truncated]" : s;
    }
}
