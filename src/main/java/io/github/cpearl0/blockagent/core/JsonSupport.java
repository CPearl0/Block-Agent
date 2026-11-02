package io.github.cpearl0.blockagent.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class JsonSupport {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonSupport() {}

    static JsonObject object(JsonElement value, String label) {
        if (value == null || !value.isJsonObject())
            throw new IllegalArgumentException(label + " must be an object");
        return value.getAsJsonObject();
    }

    static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(key + " must be text");
        return value.getAsString();
    }

    static long number(JsonObject object, String key, long fallback, long min, long max) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                throw new NumberFormatException();
            long n = value.getAsBigDecimal().longValueExact();
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be an integer in " + min + ".." + max);
        }
    }

    static boolean bool(JsonObject object, String key, boolean fallback) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(key + " must be boolean");
        return value.getAsBoolean();
    }

    static JsonObject child(JsonObject object, String key) {
        return object.has(key) ? object(object.get(key), key) : new JsonObject();
    }

    static JsonObject of(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2)
            result.add((String) pairs[i], GSON.toJsonTree(pairs[i + 1]));
        return result;
    }

    static int bytes(JsonElement value) {
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    static JsonElement bounded(JsonElement value, int maxBytes) {
        if (bytes(value) <= maxBytes) return value.deepCopy();
        String text = value.toString();
        int end = Math.min(text.length(), Math.max(0, (maxBytes - 180) / 4));
        return of(
                "truncated",
                true,
                "originalBytes",
                bytes(value),
                "jsonPreview",
                text.substring(0, end),
                "scope",
                "Preview may omit facts; omitted data is unknown");
    }

    static JsonObject readObject(Path path, int maxBytes) throws IOException {
        if (Files.size(path) > maxBytes)
            throw new IOException("JSON file exceeds size limit: " + path.getFileName());
        try {
            return object(
                    JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)),
                    path.getFileName().toString());
        } catch (JsonParseException | IllegalArgumentException ex) {
            throw new IOException("Invalid JSON file: " + path.getFileName(), ex);
        }
    }

    static void writeAtomic(Path path, JsonElement value) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp =
                Files.createTempFile(
                        path.toAbsolutePath().getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(value), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temp,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static JsonElement path(JsonElement value, String path) {
        if (path.isEmpty()) return value == null ? JsonNull.INSTANCE : value;
        for (String key : path.split("\\.")) {
            if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
            if (value.isJsonObject()) value = value.getAsJsonObject().get(key);
            else if (value.isJsonArray()) {
                try {
                    int index = Integer.parseInt(key);
                    value =
                            index >= 0 && index < value.getAsJsonArray().size()
                                    ? value.getAsJsonArray().get(index)
                                    : JsonNull.INSTANCE;
                } catch (NumberFormatException ex) {
                    return JsonNull.INSTANCE;
                }
            } else return JsonNull.INSTANCE;
        }
        return value == null ? JsonNull.INSTANCE : value;
    }
}
