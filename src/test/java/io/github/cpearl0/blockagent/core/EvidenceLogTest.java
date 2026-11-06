package io.github.cpearl0.blockagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class EvidenceLogTest {
    private static final String PAD = "EVIDENCE_PAD_DO_NOT_KEEP-" + "x".repeat(1800);

    @Test
    void inspectedSupportAndFluidFactsSurviveProjectionForRouteRepair() {
        EvidenceLog log = new EvidenceLog(131072, 20480);
        JsonObject data =
                JsonSupport.of(
                        "name",
                        "minecraft:stone",
                        "position",
                        JsonSupport.of("x", 1, "y", -55, "z", 3),
                        "fullCubeSupport",
                        true,
                        "air",
                        false,
                        "replaceable",
                        false,
                        "fluid",
                        "minecraft:water");
        log.add(receipt("inspect_block", new JsonObject(), true, "", "Block", data));
        JsonObject out =
                log.snapshot()
                        .getAsJsonArray("entries")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("outcome");
        assertTrue(out.get("fullCubeSupport").getAsBoolean());
        assertFalse(out.get("air").getAsBoolean());
        assertFalse(out.get("replaceable").getAsBoolean());
        assertEquals("minecraft:water", out.get("fluid").getAsString());
    }

    @Test
    void smallSurveyKeepsExactLayoutAndHazardsButNeverAPartiallyClippedMap() {
        JsonArray palette = new JsonArray();
        palette.add(
                JsonSupport.of(
                        "token",
                        "0",
                        "name",
                        "minecraft:air",
                        "collision",
                        "empty",
                        "fluid",
                        "",
                        "fullCubeSupport",
                        false));
        palette.add(
                JsonSupport.of(
                        "token",
                        "1",
                        "name",
                        "minecraft:stone",
                        "collision",
                        "full_cube",
                        "fluid",
                        "",
                        "fullCubeSupport",
                        true));
        palette.add(
                JsonSupport.of(
                        "token",
                        "2",
                        "name",
                        "minecraft:lava",
                        "collision",
                        "empty",
                        "fluid",
                        "minecraft:lava",
                        "fullCubeSupport",
                        false));
        JsonArray layers = new JsonArray();
        layers.add(
                JsonSupport.of(
                        "y",
                        10,
                        "blocks",
                        java.util.List.of("1 2"),
                        "standing",
                        java.util.List.of("??")));
        layers.add(
                JsonSupport.of(
                        "y",
                        11,
                        "blocks",
                        java.util.List.of("0 0"),
                        "standing",
                        java.util.List.of("CB")));
        layers.add(
                JsonSupport.of(
                        "y",
                        12,
                        "blocks",
                        java.util.List.of("0 0"),
                        "standing",
                        java.util.List.of("??")));
        JsonObject data =
                JsonSupport.of(
                        "bounds",
                        JsonSupport.of(
                                "min",
                                JsonSupport.of("x", 1, "y", 10, "z", 3),
                                "max",
                                JsonSupport.of("x", 2, "y", 12, "z", 3)),
                        "scannedCells",
                        6,
                        "palette",
                        palette,
                        "layers",
                        layers);
        JsonObject receipt = receipt("survey_area", new JsonObject(), true, "", "Survey", data);
        EvidenceLog log = new EvidenceLog(131072, 20480);
        log.add(receipt);
        JsonObject out =
                log.snapshot()
                        .getAsJsonArray("entries")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("outcome");
        assertEquals(layers, out.getAsJsonObject("localGeometry").get("layers"));
        assertEquals(palette, out.get("palette"));
        assertEquals(data.get("bounds"), out.get("bounds"));
        EvidenceLog narrow = new EvidenceLog(131072, 1000);
        narrow.add(receipt);
        assertFalse(narrow.snapshot().toString().contains("\"localGeometry\":"));
        data.addProperty("scannedCells", 4096);
        EvidenceLog large = new EvidenceLog(131072, 20480);
        large.add(receipt("survey_area", new JsonObject(), true, "", "Large survey", data));
        assertFalse(large.snapshot().toString().contains("\"localGeometry\":"));
    }

    @Test
    void renderedHistoryStaysUnderBudgetAndDropsBulkyPayloads() {
        EvidenceLog log = new EvidenceLog(131072, BotConfig.Agent.DEFAULT_RENDERED_EVIDENCE_BYTES);
        int raw = feed(log);
        JsonObject snapshot = log.snapshot();
        int rendered = JsonSupport.bytes(snapshot);
        assertTrue(raw > 200_000, "fixture receipts were not large enough to measure compression");
        assertTrue(rendered <= BotConfig.Agent.DEFAULT_RENDERED_EVIDENCE_BYTES);
        assertTrue(rendered * 6 < raw, "rendered=" + rendered + " raw=" + raw);
        assertEquals(
                BotConfig.Agent.DEFAULT_RENDERED_EVIDENCE_BYTES,
                snapshot.get("budgetBytes").getAsInt());
        assertFalse(snapshot.has("renderTrimmed"));
        assertTrue(snapshot.get("foldedEntries").getAsInt() > 0);
        assertTrue(snapshot.get("coalescedRepeats").getAsInt() >= 39);
        assertTrue(snapshot.getAsJsonArray("entries").size() <= EvidenceLog.RECENT_ENTRY_LIMIT);
        String text = snapshot.toString();
        assertFalse(text.contains("EVIDENCE_PAD_DO_NOT_KEEP"));
        assertFalse(text.contains("collisionBoxes"));
        assertFalse(text.contains("nearbyEntities"));
        assertTrue(text.contains("minecraft:deepslate"));
        assertEquals(rawAdds(snapshot), 40 + 30 + 20);
    }

    @Test
    void observeJitterCoalescesWhileNewFactsStillCount() {
        EvidenceLog log = new EvidenceLog(131072, 20480);
        log.add(observe(1, 10.2, false, 0));
        assertEquals(1, log.knowledgeRevision());
        log.add(observe(2, 10.8, false, 0));
        log.add(observe(3, 10.1, false, 0));
        assertEquals(1, log.knowledgeRevision());
        JsonArray entries = log.snapshot().getAsJsonArray("entries");
        assertEquals(1, entries.size());
        assertEquals(3, entries.get(0).getAsJsonObject().get("repeats").getAsInt());
        log.add(observe(4, 10.4, true, 0));
        assertEquals(2, log.knowledgeRevision());
        assertEquals(2, log.snapshot().getAsJsonArray("entries").size());
        log.add(observe(5, 10.3, true, 4));
        assertEquals(3, log.knowledgeRevision(), "inventory change remains knowledge");
        JsonArray after = log.snapshot().getAsJsonArray("entries");
        assertEquals(
                3, after.size(), "inventory counts stay in the prompt, so they do not coalesce");
        assertTrue(after.get(2).getAsJsonObject().toString().contains("minecraft:diamond"));
        log.add(observe(6, 14.2, true, 4));
        assertEquals(4, log.knowledgeRevision());
        assertEquals(4, log.snapshot().getAsJsonArray("entries").size());
    }

    @Test
    void menuHandleChurnIsNotNewKnowledge() {
        EvidenceLog log = new EvidenceLog(131072, 20480);
        for (int revision = 1; revision <= 4; revision++) log.add(menu(revision));
        assertEquals(1, log.knowledgeRevision());
        JsonObject entry = log.snapshot().getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals(4, entry.get("repeats").getAsInt());
        assertEquals("inspect_inventory_window", entry.get("tool").getAsString());
        JsonObject outcome = entry.getAsJsonObject("outcome");
        assertEquals(4, outcome.get("windowId").getAsInt());
        assertEquals(4, outcome.get("stateId").getAsInt());
        String text = log.snapshot().toString();
        assertTrue(text.contains("test:part"));
        assertFalse(text.contains("synchronizedAtMs"));
        assertFalse(text.contains("ChestMenu"));
    }

    @Test
    void furnaceLayoutAndLatestHandlesStayInThePrompt() {
        EvidenceLog log = new EvidenceLog(131072, 20480);
        log.add(furnace(1, 10, false));
        log.add(furnace(2, 11, false));
        log.add(dig(1, true));
        log.add(furnace(3, 12, true));
        JsonArray entries = log.snapshot().getAsJsonArray("entries");
        assertEquals(3, entries.size());
        JsonObject first = entries.get(0).getAsJsonObject().getAsJsonObject("outcome");
        assertFalse(first.has("windowId"));
        assertFalse(first.has("stateId"));
        assertTrue(first.toString().contains("FurnaceFuelSlot"));
        assertTrue(first.toString().contains("\"empty\":true"));
        JsonObject latest = entries.get(2).getAsJsonObject().getAsJsonObject("outcome");
        assertEquals(3, latest.get("windowId").getAsInt());
        assertEquals(12, latest.get("stateId").getAsInt());
        assertTrue(latest.toString().contains("minecraft:raw_iron"));
        assertTrue(latest.toString().contains("FurnaceResultSlot"));
        assertEquals(3, log.knowledgeRevision());
    }

    @Test
    void smeltingReceiptsKeepOutputProofAndInvalidateOlderHandles() {
        EvidenceLog log = new EvidenceLog(131072, 20480);
        log.add(furnace(1, 10, false));
        JsonObject data =
                furnace(1, 11, false).getAsJsonObject("result").getAsJsonObject("data").deepCopy();
        data.addProperty("itemName", "minecraft:iron_ingot");
        data.addProperty("recipeId", "minecraft:iron_ingot_from_smelting_raw_iron");
        data.addProperty("requested", 2);
        data.addProperty("produced", 2);
        data.addProperty("confirmedBy", "server_full_content");
        data.addProperty("unboundedDebug", PAD);
        log.add(
                receipt(
                        "smelt_item",
                        JsonSupport.of("itemName", "minecraft:iron_ingot", "count", 2),
                        true,
                        "",
                        "Native cooking confirmed",
                        data));
        JsonArray entries = log.snapshot().getAsJsonArray("entries");
        assertFalse(entries.get(0).getAsJsonObject().getAsJsonObject("outcome").has("stateId"));
        JsonObject outcome = entries.get(1).getAsJsonObject().getAsJsonObject("outcome");
        assertEquals(11, outcome.get("stateId").getAsInt());
        assertEquals(2, outcome.get("produced").getAsInt());
        assertEquals(2, outcome.get("requested").getAsInt());
        assertEquals("server_full_content", outcome.get("confirmedBy").getAsString());
        assertTrue(outcome.get("recipeId").getAsString().contains("smelting_raw_iron"));
        assertTrue(outcome.toString().contains("FurnaceResultSlot"));
        assertFalse(outcome.has("unboundedDebug"));
        log.add(furnace(2, 12, false));
        entries = log.snapshot().getAsJsonArray("entries");
        assertFalse(entries.get(1).getAsJsonObject().getAsJsonObject("outcome").has("windowId"));
        assertEquals(
                2,
                entries.get(2)
                        .getAsJsonObject()
                        .getAsJsonObject("outcome")
                        .get("windowId")
                        .getAsInt());
    }

    @Test
    void smeltingFailurePreservesFaultAndFurnaceContents() {
        EvidenceLog log = new EvidenceLog(131072, 20480);
        JsonObject data =
                furnace(4, 20, true).getAsJsonObject("result").getAsJsonObject("data").deepCopy();
        log.add(
                receipt(
                        "smelt_item",
                        JsonSupport.of("itemName", "minecraft:iron_ingot"),
                        false,
                        "smelting_fuel_missing",
                        "Cooking needs more compatible fuel",
                        data));
        String text = log.snapshot().toString();
        assertTrue(text.contains("smelting_fuel_missing"));
        assertTrue(text.contains("minecraft:raw_iron"));
        assertTrue(text.contains("FurnaceFuelSlot"));
        assertFalse(text.contains("\"produced\""));
    }

    @Test
    void deathAndBlockSearchSurviveTheRecentWindow() {
        EvidenceLog log = new EvidenceLog(131072, 20480);
        log.add(death());
        log.add(findDiamonds());
        for (int i = 0; i < 40; i++) log.add(dig(i, true));
        JsonObject snapshot = log.snapshot();
        assertFalse(snapshot.has("renderTrimmed"));
        String recent = snapshot.getAsJsonArray("entries").toString();
        assertFalse(recent.contains("host_respawn"));
        assertFalse(recent.contains("diamond_ore"));
        JsonObject summary = snapshot.getAsJsonObject("summary");
        String deaths = summary.getAsJsonArray("deaths").toString();
        assertTrue(deaths.contains("100"));
        assertTrue(deaths.contains("-20"));
        String highlights = summary.getAsJsonArray("highlights").toString();
        assertTrue(highlights.contains("minecraft:diamond_ore"));
        assertTrue(highlights.contains("1207"));
        assertTrue(highlights.contains("-58"));
        assertTrue(highlights.contains("9033"));
    }

    @Test
    void repeatedFailuresCoalesceApartFromSuccess() {
        EvidenceLog log = new EvidenceLog(131072, 20480);
        log.add(dig(3, false));
        log.add(dig(3, false));
        log.add(dig(3, false));
        assertEquals(0, log.knowledgeRevision());
        JsonObject failed = log.snapshot().getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals(3, failed.get("repeats").getAsInt());
        assertFalse(failed.get("ok").getAsBoolean());
        log.add(dig(3, true));
        assertEquals(1, log.knowledgeRevision());
        assertEquals(2, log.snapshot().getAsJsonArray("entries").size());
    }

    @Test
    void renderedBudgetHonorsBothKnobs() {
        EvidenceLog wide = new EvidenceLog(131072, BotConfig.Agent.DEFAULT_RENDERED_EVIDENCE_BYTES);
        EvidenceLog narrow = new EvidenceLog(131072, 6000);
        EvidenceLog capped = new EvidenceLog(8192, 20480);
        feed(wide);
        feed(narrow);
        feed(capped);
        int wideBytes = JsonSupport.bytes(wide.snapshot());
        int narrowBytes = JsonSupport.bytes(narrow.snapshot());
        int cappedBytes = JsonSupport.bytes(capped.snapshot());
        assertTrue(wideBytes <= BotConfig.Agent.DEFAULT_RENDERED_EVIDENCE_BYTES);
        assertTrue(narrowBytes <= 6000);
        assertTrue(narrowBytes < wideBytes);
        assertTrue(cappedBytes <= 8192);
        assertEquals(8192, capped.snapshot().get("budgetBytes").getAsInt());
        assertFalse(capped.snapshot().has("renderTrimmed"));
        assertFalse(narrow.snapshot().has("renderTrimmed"));
    }

    private static int feed(EvidenceLog log) {
        int raw = 0;
        for (int i = 0; i < 40; i++)
            raw += add(log, observe(i + 1, 10.2 + (i % 3) * 0.1, false, 0));
        for (int i = 0; i < 30; i++) raw += add(log, survey(i));
        for (int i = 0; i < 20; i++) raw += add(log, dig(i, true));
        return raw;
    }

    private static int add(EvidenceLog log, JsonObject receipt) {
        int bytes = JsonSupport.bytes(receipt);
        log.add(receipt);
        return bytes;
    }

    private static int rawAdds(JsonObject snapshot) {
        int seen = snapshot.get("foldedEntries").getAsInt();
        for (var value : snapshot.getAsJsonArray("entries")) {
            JsonObject entry = value.getAsJsonObject();
            seen += entry.has("repeats") ? entry.get("repeats").getAsInt() : 1;
        }
        return seen;
    }

    private static JsonObject observe(long clock, double x, boolean creeper, int diamonds) {
        JsonArray entities = new JsonArray();
        for (int i = 0; i < 48; i++)
            entities.add(
                    JsonSupport.of(
                            "id",
                            i,
                            "name",
                            "minecraft:item",
                            "displayName",
                            PAD,
                            "position",
                            JsonSupport.of("x", i + clock, "y", 64, "z", clock),
                            "distance",
                            clock + i,
                            "item",
                            JsonSupport.of(
                                    "name",
                                    "minecraft:cobblestone",
                                    "count",
                                    1,
                                    "components",
                                    JsonSupport.of("pad", PAD))));
        if (creeper)
            entities.add(
                    JsonSupport.of(
                            "id",
                            90,
                            "name",
                            "minecraft:creeper",
                            "position",
                            JsonSupport.of("x", clock, "y", 64, "z", 1),
                            "health",
                            20));
        JsonArray inventory = new JsonArray();
        inventory.add(
                JsonSupport.of(
                        "name",
                        "minecraft:diamond",
                        "count",
                        diamonds,
                        "displayName",
                        PAD,
                        "components",
                        JsonSupport.of("pad", PAD)));
        for (int i = 0; i < 30; i++)
            inventory.add(
                    JsonSupport.of(
                            "slot",
                            i,
                            "name",
                            "minecraft:cobblestone",
                            "count",
                            64,
                            "displayName",
                            PAD));
        JsonArray blocks = new JsonArray();
        for (int i = 0; i < 32; i++)
            blocks.add(JsonSupport.of("name", "minecraft:stone", "observedCount", 80 - i));
        return receipt(
                "observe",
                new JsonObject(),
                true,
                "",
                "Fresh client observation",
                JsonSupport.of(
                        "dimension",
                        "minecraft:overworld",
                        "serverId",
                        "singleplayer:world",
                        "position",
                        JsonSupport.of("x", x, "y", 64.2, "z", 20.8),
                        "health",
                        20,
                        "food",
                        18,
                        "gameTime",
                        clock,
                        "dayTime",
                        clock,
                        "menuId",
                        clock,
                        "inventory",
                        inventory,
                        "nearbyEntities",
                        entities,
                        "nearbyBlocks",
                        blocks,
                        "headBlock",
                        JsonSupport.of(
                                "name",
                                "minecraft:stone",
                                "collisionBoxes",
                                JsonSupport.of("pad", PAD))));
    }

    private static JsonObject survey(int index) {
        JsonArray layers = new JsonArray();
        for (int y = 0; y < 16; y++) layers.add(PAD + " layer " + y + " survey " + index);
        JsonArray palette = new JsonArray();
        palette.add(
                JsonSupport.of(
                        "name",
                        index == 29 ? "minecraft:deepslate" : "minecraft:stone",
                        "token",
                        "0",
                        "collisionBoxes",
                        JsonSupport.of("pad", PAD)));
        return receipt(
                "survey_area",
                JsonSupport.of(
                        "bounds",
                        JsonSupport.of("min", JsonSupport.of("x", index, "y", 0, "z", 0))),
                true,
                "",
                "Loaded area survey",
                JsonSupport.of(
                        "bounds",
                        JsonSupport.of(
                                "min",
                                JsonSupport.of("x", 0, "y", 0, "z", 0),
                                "max",
                                JsonSupport.of("x", 15, "y", 15, "z", 15)),
                        "size",
                        JsonSupport.of("x", 16, "y", 16, "z", 16),
                        "scannedCells",
                        4096,
                        "unknownCells",
                        index,
                        "palette",
                        palette,
                        "layers",
                        layers,
                        "coordinates",
                        PAD));
    }

    private static JsonObject dig(int x, boolean ok) {
        JsonObject data =
                JsonSupport.of(
                        "position",
                        JsonSupport.of("x", x, "y", 12, "z", 4),
                        "name",
                        "minecraft:stone",
                        "collisionBoxes",
                        JsonSupport.of("pad", PAD));
        return receipt(
                "dig_at",
                JsonSupport.of("position", JsonSupport.of("x", x, "y", 12, "z", 4)),
                ok,
                ok ? "" : "unreachable",
                ok ? "Target removed after server acknowledgement" : "Could not reach block",
                data);
    }

    private static JsonObject furnace(int windowId, int stateId, boolean withOre) {
        java.util.List<JsonObject> slots =
                java.util.List.of(
                        furnaceSlot(0, "net.minecraft.world.inventory.Slot", withOre),
                        furnaceSlot(1, "net.minecraft.world.inventory.FurnaceFuelSlot", false),
                        furnaceSlot(2, "net.minecraft.world.inventory.FurnaceResultSlot", false));
        return receipt(
                "inspect_inventory_window",
                new JsonObject(),
                true,
                "",
                "Authoritative native menu contents",
                JsonSupport.of(
                        "windowId",
                        windowId,
                        "stateId",
                        stateId,
                        "synchronizedAtMs",
                        50_000 + stateId,
                        "menuType",
                        "minecraft:furnace",
                        "menuClass",
                        "net.minecraft.world.inventory.FurnaceMenu",
                        "slots",
                        slots));
    }

    private static JsonObject furnaceSlot(int index, String slotClass, boolean ore) {
        JsonObject slot =
                JsonSupport.of(
                        "index",
                        index,
                        "region",
                        "container",
                        "slotClass",
                        slotClass,
                        "containerClass",
                        "net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity");
        if (ore)
            slot.add(
                    "item",
                    JsonSupport.of("name", "minecraft:raw_iron", "count", 3, "displayName", PAD));
        else slot.add("item", JsonSupport.of("name", "minecraft:air", "count", 0));
        return slot;
    }

    private static JsonObject menu(int revision) {
        return receipt(
                "inspect_inventory_window",
                new JsonObject(),
                true,
                "",
                "same actual menu contents",
                JsonSupport.of(
                        "windowId",
                        revision,
                        "stateId",
                        revision,
                        "synchronizedAtMs",
                        10000 + revision,
                        "menuType",
                        "minecraft:generic_3x3",
                        "menuClass",
                        "net.minecraft.world.inventory.ChestMenu",
                        "slots",
                        java.util.List.of(
                                JsonSupport.of(
                                        "index",
                                        0,
                                        "region",
                                        "container",
                                        "slotClass",
                                        "net.minecraft.world.inventory.Slot",
                                        "item",
                                        JsonSupport.of(
                                                "name",
                                                "test:part",
                                                "count",
                                                2,
                                                "displayName",
                                                PAD)))));
    }

    private static JsonObject death() {
        return receipt(
                "host_respawn",
                JsonSupport.of("attempt", 1),
                true,
                "",
                "Player died. Host is respawning and the same command continues.",
                JsonSupport.of(
                        "attempt",
                        1,
                        "resume",
                        "same_command",
                        "lastLivingPosition",
                        JsonSupport.of("x", 100.4, "y", 64.2, "z", -20)));
    }

    private static JsonObject findDiamonds() {
        JsonArray blocks = new JsonArray();
        blocks.add(
                JsonSupport.of(
                        "name",
                        "minecraft:diamond_ore",
                        "position",
                        JsonSupport.of("x", 1207, "y", -58, "z", 9033),
                        "distance",
                        12.5,
                        "collisionBoxes",
                        JsonSupport.of("pad", PAD),
                        "tags",
                        java.util.List.of("minecraft:mineable/pickaxe")));
        return receipt(
                "find_blocks",
                JsonSupport.of("name", "minecraft:diamond_ore", "maxDistance", 32),
                true,
                "",
                "Loaded block search",
                JsonSupport.of("blocks", blocks, "omittedMatches", 3, "unknownCells", 0));
    }

    private static JsonObject receipt(
            String tool,
            JsonObject args,
            boolean ok,
            String code,
            String message,
            JsonObject data) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", ok);
        if (!code.isBlank()) result.addProperty("code", code);
        result.addProperty("message", message);
        result.add("data", data);
        return JsonSupport.of(
                "tool", tool, "args", args, "result", result, "completedAtMs", 1_000L);
    }
}
