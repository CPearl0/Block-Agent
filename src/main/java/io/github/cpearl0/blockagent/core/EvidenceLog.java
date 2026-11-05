package io.github.cpearl0.blockagent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Prompt history for one command. Knowledge hashes still see full tool facts so noProgress keeps
 * its meaning. The object rendered into the model prompt does not: bulky observe, survey, registry
 * and container payloads are projected, identical projections coalesce, and anything outside the
 * recent window is folded into counts, failures, deaths and a few highlights.
 */
final class EvidenceLog {
    static final int RECENT_ENTRY_LIMIT = 24;

    private static final String SCOPE =
            "Compact chronological projections, not fresh world truth and not full tool payloads. Identical outcomes are coalesced; older receipts are folded into counts, failures, deaths and short highlights. Observe entries keep compact inventory counts. The latest menu receipt keeps windowId, stateId and container slots, including empty ones and slot class names. Older menu handles are omitted because stateId goes stale. Omitted detail stays unknown. Use currentSnapshot for the live world.";

    private static final Set<String> DROP =
            Set.of(
                    "collision",
                    "collisionBoxes",
                    "tags",
                    "layers",
                    "standing",
                    "ingredients",
                    "nearbyEntities",
                    "displayName",
                    "menuClass",
                    "slotClass",
                    "containerClass",
                    "itemHandlerClass",
                    "coordinates",
                    "gameTime",
                    "dayTime",
                    "timeOfDay",
                    "timestamp",
                    "observedAtMs",
                    "completedAtMs",
                    "elapsedTimeMs",
                    "elapsedMs",
                    "startedAtMs",
                    "durationMs",
                    "latencyMs",
                    "menuId",
                    "windowId",
                    "stateId",
                    "synchronizedAtMs",
                    "defaultProperties",
                    "components",
                    "componentsScope",
                    "componentOmissions",
                    "food",
                    "scope",
                    "entityUuid",
                    "distance",
                    "visibleFromCurrentEye");

    private static final List<String> TIME_KEYS =
            List.of(
                    "elapsedMs",
                    "elapsedTimeMs",
                    "completedAtMs",
                    "startedAtMs",
                    "durationMs",
                    "latencyMs");

    private final int budget;
    private final int summaryBudget;
    private final int recentBudget;
    private final int perEntryCap;
    private final ArrayDeque<JsonObject> recent = new ArrayDeque<>();
    private final ArrayDeque<String> signatures = new ArrayDeque<>();
    private final LinkedHashMap<String, ToolRollup> byTool = new LinkedHashMap<>();
    private final ArrayDeque<JsonObject> failures = new ArrayDeque<>();
    private final ArrayDeque<JsonObject> deaths = new ArrayDeque<>();
    private final LinkedHashMap<String, JsonObject> highlights = new LinkedHashMap<>();
    private final LinkedHashSet<String> knowledge = new LinkedHashSet<>();
    private int recentBytes;
    private int foldedEntries;
    private int coalescedRepeats;
    private long sequence;
    private long knowledgeRevision;

    EvidenceLog(int maxEvidenceBytes, int renderedEvidenceBytes) {
        budget = Math.min(maxEvidenceBytes, renderedEvidenceBytes);
        int envelope = Math.min(720, Math.max(240, budget / 6));
        int body = Math.max(1, budget - envelope);
        int summary = Math.max(480, body / 5);
        if (summary > body / 3) summary = Math.max(1, body / 3);
        summaryBudget = summary;
        recentBudget = Math.max(1, body - summary);
        perEntryCap = Math.min(1536, Math.max(280, recentBudget / 6));
    }

    void add(JsonObject receipt) {
        remember(receipt);
        long id = ++sequence;
        JsonObject entry = fitEntry(project(receipt, id));
        String signature = signature(entry);
        if (!signatures.isEmpty() && signature.equals(signatures.peekLast())) {
            coalesceTail(id, entry);
            return;
        }
        recent.addLast(entry);
        signatures.addLast(signature);
        recentBytes += JsonSupport.bytes(entry);
        trim();
    }

    long knowledgeRevision() {
        return knowledgeRevision;
    }

    JsonObject snapshot() {
        JsonObject root = new JsonObject();
        root.addProperty("scope", SCOPE);
        root.addProperty("foldedEntries", foldedEntries);
        root.addProperty("coalescedRepeats", coalescedRepeats);
        root.addProperty("budgetBytes", budget);
        JsonObject summary = summaryJson();
        if (summary != null) root.add("summary", summary);
        JsonArray values = new JsonArray();
        for (JsonObject entry : recent) values.add(entry.deepCopy());
        retainLatestMenuHandles(values);
        root.add("entries", values);
        while (JsonSupport.bytes(root) > budget && values.size() > 1) {
            values.remove(0);
            root.addProperty("renderTrimmed", true);
        }
        if (JsonSupport.bytes(root) > budget) {
            root.remove("summary");
            root.addProperty("renderTrimmed", true);
        }
        return root;
    }

    /**
     * Clock passage, sub-block idle jitter and a known mob walking around are not task progress.
     * Full observations still feed this hash; the prompt only receives the compact projection.
     */
    static JsonObject stableFacts(JsonObject snapshot) {
        JsonObject stable = snapshot.deepCopy();
        for (String key :
                List.of(
                        "gameTime",
                        "dayTime",
                        "timeOfDay",
                        "timestamp",
                        "observedAtMs",
                        "completedAtMs",
                        "elapsedTimeMs",
                        "menuId")) stable.remove(key);
        if (stable.has("position") && stable.get("position").isJsonObject()) {
            JsonObject position = stable.getAsJsonObject("position");
            for (String axis : List.of("x", "y", "z"))
                if (position.has(axis)
                        && position.get(axis).isJsonPrimitive()
                        && position.getAsJsonPrimitive(axis).isNumber())
                    position.addProperty(axis, Math.floor(position.get(axis).getAsDouble()));
        }
        if (stable.has("nearbyEntities") && stable.get("nearbyEntities").isJsonArray()) {
            List<JsonObject> identities = new ArrayList<>();
            for (JsonElement value : stable.getAsJsonArray("nearbyEntities"))
                if (value.isJsonObject()) {
                    JsonObject entity = value.getAsJsonObject().deepCopy();
                    entity.remove("position");
                    entity.remove("distance");
                    entity.remove("ageMs");
                    identities.add(entity);
                }
            identities.sort(Comparator.comparing(JsonObject::toString));
            JsonArray entities = new JsonArray();
            identities.forEach(entities::add);
            stable.add("nearbyEntities", entities);
        }
        if (stable.has("recentDamage") && stable.get("recentDamage").isJsonObject())
            stable.getAsJsonObject("recentDamage").remove("ageMs");
        return stable;
    }

    private void remember(JsonObject receipt) {
        String tool = receipt.get("tool").getAsString();
        JsonObject result = receipt.getAsJsonObject("result");
        if (tool.equals("wait")
                || tool.equals("chat")
                || !JsonSupport.bool(result, "ok", false)
                || !result.has("data")) return;
        JsonElement facts = result.get("data").deepCopy();
        if (tool.equals("observe") && facts.isJsonObject())
            facts = stableFacts(facts.getAsJsonObject());
        else if (facts.isJsonObject())
            for (String key : TIME_KEYS) facts.getAsJsonObject().remove(key);
        if ((tool.equals("inspect_inventory_window") || tool.equals("transfer_inventory_slot"))
                && facts.isJsonObject())
            for (String key : List.of("windowId", "stateId", "synchronizedAtMs"))
                facts.getAsJsonObject().remove(key);
        String raw = tool + "|" + receipt.get("args") + "|" + facts;
        if (knowledge.add(sha256(raw))) knowledgeRevision++;
        while (knowledge.size() > 512) knowledge.remove(knowledge.iterator().next());
    }

    private void coalesceTail(long id, JsonObject incoming) {
        JsonObject tail = recent.peekLast();
        int before = JsonSupport.bytes(tail);
        int repeats = tail.has("repeats") ? tail.get("repeats").getAsInt() + 1 : 2;
        tail.addProperty("repeats", repeats);
        tail.addProperty("lastSequence", id);
        refreshMenuHandles(tail, incoming);
        recentBytes += JsonSupport.bytes(tail) - before;
        coalescedRepeats++;
        trim();
    }

    /** Handle churn is not a new outcome, but the prompt must show the newest ids. */
    private static void refreshMenuHandles(JsonObject tail, JsonObject incoming) {
        if (!tail.has("outcome") || !tail.get("outcome").isJsonObject()) return;
        if (!incoming.has("outcome") || !incoming.get("outcome").isJsonObject()) return;
        JsonObject from = incoming.getAsJsonObject("outcome");
        JsonObject to = tail.getAsJsonObject("outcome");
        for (String key : List.of("windowId", "stateId"))
            if (from.has(key)) to.add(key, from.get(key).deepCopy());
    }

    private static void retainLatestMenuHandles(JsonArray entries) {
        int latest = -1;
        for (int i = 0; i < entries.size(); i++)
            if (isMenuTool(entries.get(i).getAsJsonObject())) latest = i;
        for (int i = 0; i < entries.size(); i++) {
            if (i == latest) continue;
            JsonObject entry = entries.get(i).getAsJsonObject();
            if (!isMenuTool(entry) || !entry.has("outcome") || !entry.get("outcome").isJsonObject())
                continue;
            JsonObject outcome = entry.getAsJsonObject("outcome");
            outcome.remove("windowId");
            outcome.remove("stateId");
        }
    }

    private static boolean isMenuTool(JsonObject entry) {
        String tool = entry.get("tool").getAsString();
        return tool.equals("inspect_inventory_window")
                || tool.equals("transfer_inventory_slot")
                || tool.equals("craft_item")
                || tool.equals("close_inventory_window");
    }

    private void trim() {
        while (recent.size() > RECENT_ENTRY_LIMIT
                || (recent.size() > 1 && recentBytes > recentBudget)) {
            JsonObject oldest = recent.removeFirst();
            signatures.removeFirst();
            recentBytes -= JsonSupport.bytes(oldest);
            fold(oldest);
        }
    }

    private void fold(JsonObject entry) {
        int copies = repeats(entry);
        foldedEntries += copies;
        String tool = entry.get("tool").getAsString();
        ToolRollup roll = byTool.computeIfAbsent(tool, ignored -> new ToolRollup());
        roll.calls += copies;
        if (entry.get("ok").getAsBoolean()) roll.ok += copies;
        else roll.failed += copies;
        roll.lastSequence = lastSequence(entry);
        if (!entry.get("ok").getAsBoolean()) rememberFailure(entry);
        if (tool.equals("host_respawn")) rememberDeath(entry);
        else if (entry.get("ok").getAsBoolean() && notable(tool)) rememberHighlight(entry);
    }

    private void rememberFailure(JsonObject entry) {
        String key =
                entry.get("tool").getAsString()
                        + "|"
                        + entry.get("code")
                        + "|"
                        + entry.get("message");
        if (!failures.isEmpty()) {
            JsonObject last = failures.peekLast();
            if (key.equals(last.get("key").getAsString())) {
                last.addProperty(
                        "repeats", last.has("repeats") ? last.get("repeats").getAsInt() + 1 : 2);
                last.addProperty("sequence", lastSequence(entry));
                return;
            }
        }
        JsonObject failure = new JsonObject();
        failure.addProperty("key", key);
        failure.addProperty("sequence", lastSequence(entry));
        failure.addProperty("tool", entry.get("tool").getAsString());
        if (entry.has("code"))
            failure.addProperty("code", clip(entry.get("code").getAsString(), 80));
        if (entry.has("message"))
            failure.addProperty("message", clip(entry.get("message").getAsString(), 140));
        if (entry.has("args")) failure.add("args", shrink(entry.get("args"), 0, 2, 80));
        if (JsonSupport.bytes(failure) > 420) failure.remove("args");
        failures.addLast(failure);
        while (failures.size() > 6) failures.removeFirst();
    }

    private void rememberDeath(JsonObject entry) {
        JsonObject death = new JsonObject();
        death.addProperty("sequence", lastSequence(entry));
        if (entry.has("outcome") && entry.get("outcome").isJsonObject()) {
            JsonObject outcome = entry.getAsJsonObject("outcome");
            if (outcome.has("attempt")) death.add("attempt", outcome.get("attempt").deepCopy());
            if (outcome.has("lastLivingPosition"))
                death.add("lastLivingPosition", outcome.get("lastLivingPosition").deepCopy());
        }
        deaths.addLast(death);
        while (deaths.size() > 8) deaths.removeFirst();
    }

    private void rememberHighlight(JsonObject entry) {
        String key = entry.get("tool").getAsString() + "|" + entry.get("args");
        highlights.remove(key);
        highlights.put(key, compactHighlight(entry));
        while (highlights.size() > 8) {
            String victim = null;
            for (String candidate : highlights.keySet()) {
                int bar = candidate.indexOf('|');
                String tool = bar < 0 ? candidate : candidate.substring(0, bar);
                if (!tool.equals("find_blocks") && !tool.equals("query_registry")) {
                    victim = candidate;
                    break;
                }
            }
            if (victim == null) victim = highlights.keySet().iterator().next();
            highlights.remove(victim);
        }
    }

    private JsonObject summaryJson() {
        if (foldedEntries == 0 && deaths.isEmpty()) return null;
        JsonObject summary = new JsonObject();
        JsonArray deathArray = new JsonArray();
        for (JsonObject death : deaths) deathArray.add(death.deepCopy());
        if (!deathArray.isEmpty()) summary.add("deaths", deathArray);
        JsonObject tools = new JsonObject();
        for (var rollup : byTool.entrySet()) tools.add(rollup.getKey(), rollup.getValue().toJson());
        if (!tools.isEmpty()) summary.add("tools", tools);
        while (JsonSupport.bytes(summary) > summaryBudget && !tools.isEmpty()) {
            tools.remove(tools.keySet().iterator().next());
            summary.addProperty("summaryTruncated", true);
        }
        while (JsonSupport.bytes(summary) > summaryBudget && deathArray.size() > 1) {
            deathArray.remove(0);
            summary.addProperty("summaryTruncated", true);
        }
        fitList(summary, "highlights", orderedHighlights());
        fitList(summary, "failures", failures);
        if (JsonSupport.bytes(summary) > summaryBudget) {
            summary.remove("highlights");
            summary.remove("failures");
            summary.addProperty("summaryTruncated", true);
        }
        stripInternal(summary);
        return summary;
    }

    private List<JsonObject> orderedHighlights() {
        List<JsonObject> ordered = new ArrayList<>();
        List<JsonObject> searches = new ArrayList<>();
        for (var highlight : highlights.entrySet()) {
            int bar = highlight.getKey().indexOf('|');
            String tool = bar < 0 ? highlight.getKey() : highlight.getKey().substring(0, bar);
            if (tool.equals("find_blocks") || tool.equals("query_registry"))
                searches.add(highlight.getValue());
            else ordered.add(highlight.getValue());
        }
        ordered.addAll(searches);
        return ordered;
    }

    private void fitList(JsonObject summary, String key, Iterable<JsonObject> items) {
        JsonArray array = new JsonArray();
        for (JsonObject item : items) array.add(item.deepCopy());
        if (array.isEmpty()) return;
        summary.add(key, array);
        boolean dropped = false;
        while (JsonSupport.bytes(summary) > summaryBudget && !array.isEmpty()) {
            array.remove(0);
            dropped = true;
        }
        if (array.isEmpty()) summary.remove(key);
        if (dropped) summary.addProperty("summaryTruncated", true);
    }

    private static void stripInternal(JsonObject summary) {
        if (!summary.has("failures") || !summary.get("failures").isJsonArray()) return;
        for (JsonElement value : summary.getAsJsonArray("failures"))
            if (value.isJsonObject()) value.getAsJsonObject().remove("key");
    }

    private JsonObject project(JsonObject receipt, long id) {
        JsonObject result = receipt.getAsJsonObject("result");
        JsonObject entry = new JsonObject();
        entry.addProperty("tool", receipt.get("tool").getAsString());
        entry.addProperty("sequence", id);
        entry.addProperty("ok", JsonSupport.bool(result, "ok", false));
        String code = JsonSupport.string(result, "code", "");
        if (!code.isBlank()) entry.addProperty("code", clip(code, 80));
        String message = JsonSupport.string(result, "message", "");
        if (!message.isBlank()) entry.addProperty("message", clip(message, 160));
        JsonElement args = receipt.get("args");
        if (args != null && !args.isJsonNull()) {
            JsonElement projected = shrink(args, 0, 6, 120);
            if (!projected.isJsonObject() || !projected.getAsJsonObject().isEmpty())
                entry.add("args", projected);
        }
        if (result.has("data") && !result.get("data").isJsonNull()) {
            JsonElement outcome =
                    projectOutcome(entry.get("tool").getAsString(), result.get("data"));
            if (outcome != null && !outcome.isJsonNull()) entry.add("outcome", outcome);
        }
        return entry;
    }

    private JsonElement projectOutcome(String tool, JsonElement data) {
        if (!data.isJsonObject()) return shrink(data, 0, 8, 120);
        JsonObject object = data.getAsJsonObject();
        return switch (tool) {
            case "observe" -> projectObserve(object);
            case "survey_area" -> projectSurvey(object);
            case "query_registry" -> projectRegistry(object);
            case "find_blocks", "inspect_block" -> projectBlocks(object);
            case "inspect_inventory_window",
                    "transfer_inventory_slot",
                    "craft_item",
                    "close_inventory_window" ->
                    projectMenu(object);
            case "host_respawn" -> projectDeath(object);
            default -> shrink(object, 0, 8, 120);
        };
    }

    private static JsonObject projectObserve(JsonObject data) {
        JsonObject out = new JsonObject();
        copyPosition(data, out, "position");
        copyString(data, out, "dimension");
        copyNumber(data, out, "health");
        copyNumber(data, out, "food");
        copyBool(data, out, "menuOpen");
        copyBool(data, out, "inWater");
        copyBool(data, out, "onFire");
        copyBool(data, out, "screenBlocksActions");
        if (data.has("headBlock") && data.get("headBlock").isJsonObject()) {
            String name = text(data.getAsJsonObject("headBlock"), "name");
            if (name != null && !name.isBlank()) out.addProperty("headBlock", name);
        }
        if (data.has("inventory") && data.get("inventory").isJsonArray()) {
            TreeMap<String, Long> counts = new TreeMap<>();
            for (JsonElement value : data.getAsJsonArray("inventory")) {
                if (!value.isJsonObject()) continue;
                JsonObject item = compactItem(value.getAsJsonObject());
                if (item == null) continue;
                counts.merge(
                        item.get("name").getAsString(), item.get("count").getAsLong(), Long::sum);
            }
            JsonArray inventory = new JsonArray();
            int omitted = 0;
            for (var stack : counts.entrySet()) {
                if (inventory.size() >= 16) {
                    omitted++;
                    continue;
                }
                JsonObject row = new JsonObject();
                row.addProperty("name", stack.getKey());
                row.addProperty("count", stack.getValue());
                inventory.add(row);
            }
            if (!inventory.isEmpty()) out.add("inventory", inventory);
            if (omitted > 0) out.addProperty("omittedItems", omitted);
        }
        if (data.has("nearbyBlocks") && data.get("nearbyBlocks").isJsonArray()) {
            List<JsonObject> blocks = new ArrayList<>();
            for (JsonElement value : data.getAsJsonArray("nearbyBlocks"))
                if (value.isJsonObject() && text(value.getAsJsonObject(), "name") != null)
                    blocks.add(value.getAsJsonObject());
            blocks.sort(
                    Comparator.comparingLong(
                                    (JsonObject block) -> -number(block, "observedCount", 0))
                            .thenComparing(block -> text(block, "name")));
            JsonArray kept = new JsonArray();
            int omitted = 0;
            for (JsonObject block : blocks) {
                if (kept.size() >= 4) {
                    omitted++;
                    continue;
                }
                JsonObject row = new JsonObject();
                row.addProperty("name", text(block, "name"));
                row.addProperty("observedCount", number(block, "observedCount", 0));
                kept.add(row);
            }
            if (!kept.isEmpty()) out.add("nearbyBlocks", kept);
            if (omitted > 0) out.addProperty("omittedBlocks", omitted);
        }
        if (data.has("nearbyEntities") && data.get("nearbyEntities").isJsonArray()) {
            List<JsonObject> entities = new ArrayList<>();
            for (JsonElement value : data.getAsJsonArray("nearbyEntities")) {
                if (!value.isJsonObject()) continue;
                JsonObject entity = value.getAsJsonObject();
                String name = text(entity, "name");
                if (name == null || name.isBlank()) continue;
                JsonObject row = new JsonObject();
                row.addProperty("name", name);
                if (hasNumber(entity, "id")) row.add("id", entity.get("id").deepCopy());
                if (hasNumber(entity, "health")) row.add("health", entity.get("health").deepCopy());
                entities.add(row);
            }
            entities.sort(
                    Comparator.comparing(
                                    (JsonObject entity) ->
                                            "minecraft:item".equals(text(entity, "name")))
                            .thenComparing(entity -> text(entity, "name"))
                            .thenComparingLong(entity -> number(entity, "id", 0)));
            JsonArray kept = new JsonArray();
            int omitted = 0;
            for (JsonObject entity : entities) {
                if (kept.size() >= 4) {
                    omitted++;
                    continue;
                }
                kept.add(entity);
            }
            if (!kept.isEmpty()) out.add("entities", kept);
            if (omitted > 0) out.addProperty("omittedEntities", omitted);
        }
        return out;
    }

    private static JsonObject projectSurvey(JsonObject data) {
        JsonObject out = new JsonObject();
        copyObject(data, out, "bounds");
        copyObject(data, out, "size");
        copyNumber(data, out, "scannedCells");
        copyNumber(data, out, "unknownCells");
        copyNumber(data, out, "loadedCells");
        if (data.has("palette") && data.get("palette").isJsonArray()) {
            JsonArray palette = new JsonArray();
            int omitted = 0;
            for (JsonElement value : data.getAsJsonArray("palette")) {
                if (!value.isJsonObject()) continue;
                if (palette.size() >= 12) {
                    omitted++;
                    continue;
                }
                JsonObject block = value.getAsJsonObject();
                JsonObject row = new JsonObject();
                String name = text(block, "name");
                if (name != null) row.addProperty("name", name);
                if (block.has("token")) row.add("token", block.get("token").deepCopy());
                if (!row.isEmpty()) palette.add(row);
            }
            if (!palette.isEmpty()) out.add("palette", palette);
            if (omitted > 0) out.addProperty("omittedPalette", omitted);
        }
        return out;
    }

    private static JsonObject projectRegistry(JsonObject data) {
        JsonObject out = new JsonObject();
        copyNumber(data, out, "omittedMatches");
        JsonArray matches = new JsonArray();
        if (data.has("values") && data.get("values").isJsonArray()) {
            int omitted = 0;
            for (JsonElement value : data.getAsJsonArray("values")) {
                if (!value.isJsonObject()) continue;
                if (matches.size() >= 8) {
                    omitted++;
                    continue;
                }
                JsonObject item = value.getAsJsonObject();
                String label = text(item, "id");
                if (label == null) label = text(item, "name");
                if (label == null && item.has("output") && item.get("output").isJsonObject())
                    label = text(item.getAsJsonObject("output"), "name");
                if (label != null) matches.add(label);
            }
            if (omitted > 0) out.addProperty("omittedListed", omitted);
        }
        if (!matches.isEmpty()) out.add("matches", matches);
        return out;
    }

    private static JsonObject projectBlocks(JsonObject data) {
        if (data.has("blocks") && data.get("blocks").isJsonArray()) {
            JsonObject out = new JsonObject();
            JsonArray blocks = new JsonArray();
            int omitted = 0;
            for (JsonElement value : data.getAsJsonArray("blocks")) {
                if (!value.isJsonObject()) continue;
                if (blocks.size() >= 8) {
                    omitted++;
                    continue;
                }
                blocks.add(projectBlock(value.getAsJsonObject()));
            }
            out.add("blocks", blocks);
            if (omitted > 0) out.addProperty("omittedListed", omitted);
            copyNumber(data, out, "omittedMatches");
            copyNumber(data, out, "unknownCells");
            return out;
        }
        return projectBlock(data);
    }

    private static JsonObject projectBlock(JsonObject block) {
        JsonObject out = new JsonObject();
        copyString(block, out, "name");
        copyPosition(block, out, "position");
        if (block.has("openFaces") && block.get("openFaces").isJsonArray()) {
            JsonArray faces = new JsonArray();
            for (JsonElement face : block.getAsJsonArray("openFaces")) {
                if (faces.size() >= 6) break;
                if (face.isJsonPrimitive()) faces.add(face.deepCopy());
            }
            if (!faces.isEmpty()) out.add("openFaces", faces);
        }
        copyNumber(block, out, "hardness");
        copyBool(block, out, "requiresCorrectTool");
        copyBool(block, out, "correctHeldTool");
        return out;
    }

    private static JsonObject projectMenu(JsonObject data) {
        JsonObject out = new JsonObject();
        copyString(data, out, "menuType");
        copyNumber(data, out, "windowId");
        copyNumber(data, out, "stateId");
        copyString(data, out, "recipeId");
        copyString(data, out, "itemName");
        copyString(data, out, "confirmedBy");
        copyNumber(data, out, "produced");
        copyNumber(data, out, "requested");
        copyNumber(data, out, "nativeBatches");
        copyNumber(data, out, "gained");
        copyNumber(data, out, "slotCount");
        copyPosition(data, out, "position");
        if (data.has("cursor") && data.get("cursor").isJsonObject()) {
            JsonObject cursor = compactItem(data.getAsJsonObject("cursor"));
            if (cursor != null) out.add("cursor", cursor);
        }
        if (data.has("slots") && data.get("slots").isJsonArray()) {
            List<JsonObject> container = new ArrayList<>();
            List<JsonObject> player = new ArrayList<>();
            int omittedEmpty = 0;
            for (JsonElement value : data.getAsJsonArray("slots")) {
                if (!value.isJsonObject()) continue;
                JsonObject slot = value.getAsJsonObject();
                String region = text(slot, "region");
                JsonObject item =
                        slot.has("item") && slot.get("item").isJsonObject()
                                ? compactItem(slot.getAsJsonObject("item"))
                                : null;
                if (item == null) {
                    if (!"container".equals(region)) continue;
                    if (container.size() >= 12) {
                        omittedEmpty++;
                        continue;
                    }
                    container.add(slotRow(slot, region, null));
                    continue;
                }
                JsonObject row = slotRow(slot, region, item);
                if ("container".equals(region)) container.add(row);
                else player.add(row);
            }
            JsonArray slots = new JsonArray();
            int omitted = omittedEmpty;
            for (JsonObject row : container) {
                if (slots.size() >= 16) omitted++;
                else slots.add(row);
            }
            for (JsonObject row : player) {
                if (slots.size() >= 16) omitted++;
                else slots.add(row);
            }
            if (!slots.isEmpty()) out.add("slots", slots);
            if (omitted > 0) out.addProperty("omittedSlots", omitted);
        }
        if (data.has("inventoryDelta") && data.get("inventoryDelta").isJsonObject())
            out.add("inventoryDelta", shrink(data.get("inventoryDelta"), 0, 8, 80));
        return out;
    }

    private static JsonObject projectDeath(JsonObject data) {
        JsonObject out = new JsonObject();
        copyNumber(data, out, "attempt");
        copyString(data, out, "resume");
        copyPosition(data, out, "lastLivingPosition");
        return out;
    }

    private static JsonObject slotRow(JsonObject slot, String region, JsonObject item) {
        JsonObject row = new JsonObject();
        if (hasNumber(slot, "index")) row.add("index", slot.get("index").deepCopy());
        if (region != null) row.addProperty("region", region);
        if ("container".equals(region)) {
            String kind = containerSlotKind(slot);
            if (kind != null) row.addProperty("slot", kind);
        }
        if (item == null) row.addProperty("empty", true);
        else {
            row.addProperty("name", item.get("name").getAsString());
            row.add("count", item.get("count").deepCopy());
        }
        return row;
    }

    private static String containerSlotKind(JsonObject slot) {
        String cls = text(slot, "slotClass");
        if (cls == null || cls.isBlank()) return null;
        int dot = cls.lastIndexOf('.');
        String simple = dot < 0 ? cls : cls.substring(dot + 1);
        return simple.equals("Slot") ? null : simple;
    }

    private static JsonObject compactItem(JsonObject item) {
        String name = text(item, "name");
        if (name == null || name.isBlank() || name.equals("minecraft:air")) return null;
        long count = number(item, "count", 1);
        if (count <= 0) return null;
        JsonObject out = new JsonObject();
        out.addProperty("name", name);
        out.addProperty("count", count);
        return out;
    }

    private static JsonObject compactHighlight(JsonObject entry) {
        JsonObject highlight = new JsonObject();
        highlight.addProperty("tool", entry.get("tool").getAsString());
        highlight.addProperty("sequence", lastSequence(entry));
        if (entry.has("args")) highlight.add("args", shrink(entry.get("args"), 0, 4, 80));
        if (entry.has("outcome")) highlight.add("outcome", shrink(entry.get("outcome"), 0, 4, 80));
        if (JsonSupport.bytes(highlight) > 640) highlight.remove("outcome");
        return highlight;
    }

    private JsonObject fitEntry(JsonObject entry) {
        if (JsonSupport.bytes(entry) <= perEntryCap) return entry;
        if (entry.has("message"))
            entry.addProperty("message", clip(entry.get("message").getAsString(), 80));
        trimArrays(entry, 4);
        if (JsonSupport.bytes(entry) <= perEntryCap) return entry;
        trimArrays(entry, 2);
        if (JsonSupport.bytes(entry) <= perEntryCap) return entry;
        entry.remove("outcome");
        entry.addProperty("outcomeOmitted", true);
        return entry;
    }

    private static void trimArrays(JsonElement value, int cap) {
        if (value == null || (!value.isJsonArray() && !value.isJsonObject())) return;
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            while (array.size() > cap) array.remove(array.size() - 1);
            for (JsonElement child : array) trimArrays(child, cap);
            return;
        }
        for (var child : value.getAsJsonObject().entrySet()) trimArrays(child.getValue(), cap);
    }

    private static JsonElement shrink(JsonElement value, int depth, int arrayCap, int stringCap) {
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isString()) {
                String text = value.getAsString();
                if (text.length() > stringCap)
                    return new JsonPrimitive(text.substring(0, Math.max(0, stringCap - 1)) + "…");
            }
            return value.deepCopy();
        }
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray();
            int kept = 0;
            for (JsonElement child : value.getAsJsonArray()) {
                if (kept++ >= arrayCap) break;
                out.add(shrink(child, depth + 1, arrayCap, stringCap));
            }
            return out;
        }
        JsonObject source = value.getAsJsonObject();
        JsonObject out = new JsonObject();
        boolean position = isPosition(source);
        int scalars = 0;
        for (var field : source.entrySet()) {
            if (DROP.contains(field.getKey())) continue;
            if (position && List.of("x", "y", "z").contains(field.getKey())) continue;
            if (depth >= 4) {
                if (!field.getValue().isJsonPrimitive() || scalars++ >= 6) continue;
            }
            out.add(field.getKey(), shrink(field.getValue(), depth + 1, arrayCap, stringCap));
        }
        if (position) {
            JsonObject floored = floorPosition(source);
            for (String axis : List.of("x", "y", "z"))
                if (floored.has(axis)) out.add(axis, floored.get(axis).deepCopy());
        }
        return out;
    }

    private static boolean isPosition(JsonObject object) {
        return hasNumber(object, "x") && hasNumber(object, "y") && hasNumber(object, "z");
    }

    private static void copyPosition(JsonObject source, JsonObject target, String key) {
        if (!source.has(key) || !source.get(key).isJsonObject()) return;
        JsonObject position = floorPosition(source.getAsJsonObject(key));
        if (!position.isEmpty()) target.add(key, position);
    }

    private static JsonObject floorPosition(JsonObject position) {
        JsonObject out = new JsonObject();
        for (String axis : List.of("x", "y", "z")) {
            if (!hasNumber(position, axis)) continue;
            out.addProperty(axis, (long) Math.floor(position.get(axis).getAsDouble()));
        }
        return out;
    }

    private static void copyString(JsonObject source, JsonObject target, String key) {
        String value = text(source, key);
        if (value != null && !value.isBlank()) target.addProperty(key, value);
    }

    private static void copyNumber(JsonObject source, JsonObject target, String key) {
        if (hasNumber(source, key)) target.add(key, source.get(key).deepCopy());
    }

    private static void copyBool(JsonObject source, JsonObject target, String key) {
        if (source.has(key)
                && source.get(key).isJsonPrimitive()
                && source.get(key).getAsJsonPrimitive().isBoolean())
            target.add(key, source.get(key).deepCopy());
    }

    private static void copyObject(JsonObject source, JsonObject target, String key) {
        if (source.has(key) && source.get(key).isJsonObject())
            target.add(key, shrink(source.get(key), 0, 4, 80));
    }

    private static String text(JsonObject object, String key) {
        if (object == null
                || !object.has(key)
                || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isString()) return null;
        return object.get(key).getAsString();
    }

    private static boolean hasNumber(JsonObject object, String key) {
        return object.has(key)
                && object.get(key).isJsonPrimitive()
                && object.get(key).getAsJsonPrimitive().isNumber();
    }

    private static long number(JsonObject object, String key, long fallback) {
        if (!hasNumber(object, key)) return fallback;
        return object.get(key).getAsLong();
    }

    private static String signature(JsonObject entry) {
        JsonElement outcome = entry.get("outcome");
        if (outcome != null && outcome.isJsonObject()) {
            JsonObject copy = outcome.getAsJsonObject().deepCopy();
            copy.remove("windowId");
            copy.remove("stateId");
            copy.remove("synchronizedAtMs");
            outcome = copy;
        }
        return sha256(
                entry.get("tool")
                        + "|"
                        + entry.get("ok")
                        + "|"
                        + entry.get("code")
                        + "|"
                        + entry.get("message")
                        + "|"
                        + entry.get("args")
                        + "|"
                        + outcome);
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String clip(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static int repeats(JsonObject entry) {
        return entry.has("repeats") ? entry.get("repeats").getAsInt() : 1;
    }

    private static long lastSequence(JsonObject entry) {
        return entry.has("lastSequence")
                ? entry.get("lastSequence").getAsLong()
                : entry.get("sequence").getAsLong();
    }

    private static boolean notable(String tool) {
        return switch (tool) {
            case "craft_item",
                    "collect_block",
                    "pickup_items",
                    "place_block",
                    "eat",
                    "find_blocks",
                    "query_registry",
                    "survey_area" ->
                    true;
            default -> false;
        };
    }

    private static final class ToolRollup {
        int calls, ok, failed;
        long lastSequence;

        JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("calls", calls);
            value.addProperty("ok", ok);
            value.addProperty("failed", failed);
            value.addProperty("lastSequence", lastSequence);
            return value;
        }
    }
}
