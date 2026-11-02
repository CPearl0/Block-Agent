package io.github.cpearl0.blockagent.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * Stable, bounded model observations; native stack equality and transfers use the original
 * components.
 */
final class ItemComponents {
    private static final int MAX_ENTRIES = 16, MAX_VALUE_BYTES = 1536, MAX_TOTAL_BYTES = 2048;

    private ItemComponents() {}

    static void addPreview(JsonObject target, ItemStack stack) {
        JsonObject values = new JsonObject();
        target.add("components", values);
        target.addProperty("componentsScope", "nondefault_patch; registered item defaults omitted");
        if (stack.isEmpty() || stack.getComponentsPatch().isEmpty()) return;
        var entries = new ArrayList<>(stack.getComponentsPatch().entrySet());
        entries.sort(Comparator.comparing(entry -> identifier(entry.getKey())));
        var mc = Minecraft.getInstance();
        DynamicOps<JsonElement> ops =
                mc.level == null
                        ? JsonOps.INSTANCE
                        : mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        JsonArray omissions = new JsonArray();
        int index = 0, bytes = 2, omitted = 0;
        for (var entry : entries) {
            if (index++ >= MAX_ENTRIES) {
                omitted++;
                continue;
            }
            String id = identifier(entry.getKey()),
                    key = entry.getValue().isEmpty() ? "!" + id : id;
            JsonObject omission = new JsonObject();
            omission.addProperty("id", key);
            JsonElement encoded;
            try {
                if (entry.getValue().isEmpty()) encoded = new JsonObject();
                else {
                    if (entry.getKey().codec() == null) {
                        omission.addProperty("reason", "nonpersistent_component");
                        omissions.add(omission);
                        omitted++;
                        continue;
                    }
                    encoded = encode(entry.getKey(), entry.getValue().get(), ops);
                    if (encoded == null) {
                        omission.addProperty("reason", "codec_could_not_encode");
                        omissions.add(omission);
                        omitted++;
                        continue;
                    }
                    encoded = canonical(encoded, 0, new int[] {2048});
                }
            } catch (RuntimeException failure) {
                omission.addProperty("reason", "encoding_or_preview_limit");
                omissions.add(omission);
                omitted++;
                continue;
            }
            byte[] data = encoded.toString().getBytes(StandardCharsets.UTF_8);
            int cost = data.length + key.getBytes(StandardCharsets.UTF_8).length + 4;
            if (data.length > MAX_VALUE_BYTES || bytes + cost > MAX_TOTAL_BYTES) {
                omission.addProperty("reason", "size_limit");
                omission.addProperty("encodedBytes", data.length);
                omission.addProperty("sha256", digest(data));
                omissions.add(omission);
                omitted++;
                continue;
            }
            values.add(key, encoded);
            bytes += cost;
        }
        if (omitted > 0) {
            target.add("componentsOmitted", omissions);
            target.addProperty("componentsOmittedCount", omitted);
            target.addProperty("componentsPatchCount", entries.size());
        }
    }

    private static String identifier(DataComponentType<?> type) {
        var id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
        return id == null ? "unregistered_component" : id.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T> JsonElement encode(
            DataComponentType<T> type, Object value, DynamicOps<JsonElement> ops) {
        return type.codec().encodeStart(ops, (T) value).result().orElse(null);
    }

    private static JsonElement canonical(JsonElement value, int depth, int[] remaining) {
        if (depth > 32 || --remaining[0] < 0)
            throw new IllegalArgumentException("Component preview limit");
        if (value.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            value.getAsJsonObject().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            entry ->
                                    sorted.add(
                                            entry.getKey(),
                                            canonical(entry.getValue(), depth + 1, remaining)));
            return sorted;
        }
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement entry : value.getAsJsonArray())
                array.add(canonical(entry, depth + 1, remaining));
            return array;
        }
        return value.deepCopy();
    }

    private static String digest(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
