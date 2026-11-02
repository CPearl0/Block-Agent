package io.github.cpearl0.blockagent.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class ToolDefinitions {
    private ToolDefinitions() {}

    static JsonArray all() {
        JsonArray tools = new JsonArray();
        add(
                tools,
                "observe",
                "Read fresh player position, survival state, inventory, nearby blocks/entities and current menu. All observations are client-visible; unknown/unloaded terrain is not air.",
                "{}");
        add(
                tools,
                "inspect_block",
                "Read one loaded block including namespaced identifier, real properties, tags, collision shape and fluid.",
                "{\"position\":{\"$ref\":\"#/$defs/position\"}}");
        add(
                tools,
                "find_blocks",
                "Search loaded blocks within maxDistance <=32, returning at most count <=64 nearest matches. name accepts exact namespaced block ID, #namespace:tag or text query. A local empty result is not global absence.",
                "{\"name\":{\"type\":\"string\"},\"maxDistance\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":32},\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":64}}");
        add(
                tools,
                "query_registry",
                "Query actual client block/item/recipe registry by namespaced ID or text. kind=blocks/items/recipes, query, count<=64. Recipes are synchronized client knowledge, not a guarantee that every mod process is exposed.",
                "{\"kind\":{\"enum\":[\"blocks\",\"items\",\"recipes\"]},\"query\":{\"type\":\"string\"},\"count\":{\"type\":\"integer\",\"maximum\":64}}");
        add(
                tools,
                "survey_area",
                "Read a bounded loaded region, bounds:{min,max} inclusive, axes<=16 and cells<=4096. Unknown cells remain unknown; includes real block properties. Does not move/load chunks.",
                "{\"bounds\":{\"type\":\"object\"}}");
        add(
                tools,
                "find_standable_positions",
                "Observe nearby loaded dry supported body-clear integer-foot positions. radius<=16, count<=64; candidates do not certify a route.",
                "{\"radius\":{\"type\":\"integer\",\"maximum\":16},\"count\":{\"type\":\"integer\",\"maximum\":64}}");
        add(
                tools,
                "find_valid_placements",
                "Observe nearby replaceable cells with an adjacent loaded solid support. Candidates do not certify actual reach, visibility or item-specific placement.",
                "{\"radius\":{\"type\":\"integer\",\"maximum\":16},\"count\":{\"type\":\"integer\",\"maximum\":64}}");
        add(
                tools,
                "move_to",
                "Walk using bounded loaded-terrain A* and native player controls to an integer foot cell within range. No implicit digging/building, teleporting or flying. Rechecks collision and support while moving.",
                "{\"position\":{\"$ref\":\"#/$defs/position\"},\"range\":{\"type\":\"number\",\"minimum\":0,\"maximum\":8}}");
        add(
                tools,
                "dig_at",
                "Mine exactly one observed block position through native survival interaction. Optional toolName selects a real inventory tool; verifies server prediction acknowledgement and actual removal. Does not certify pickup.",
                "{\"position\":{\"$ref\":\"#/$defs/position\"},\"toolName\":{\"type\":\"string\"}}");
        add(
                tools,
                "collect_block",
                "Mine one exact position (or closest locally matching name), then approach nearby drops and verify matching server inventory gain with no remaining local matching drops. Prefer explicit itemNames when expected drop IDs are known. Otherwise infer item types only from new item entities or same-item count increases actually observed within two blocks against a pre-mining entity baseline; continue scanning for late spawns. Unknown drop type cannot succeed from unrelated inventory gain. Returns actualInventoryDelta and bounded dropAssociation evidence; local temporal/spatial association does not prove exclusive source attribution or server-hidden loot tables. Partial/unconfirmed pickup fails.",
                "{\"position\":{\"$ref\":\"#/$defs/position\"},\"name\":{\"type\":\"string\"},\"toolName\":{\"type\":\"string\"},\"itemNames\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}");
        add(
                tools,
                "pickup_items",
                "Sweep observed item entities near center/radius<=16, optionally filtered by itemNames. Uses native navigation and verifies real inventory increases; never changes terrain. durationMs<=15000. Reports remaining local entities; no guarantee about later spawns.",
                "{\"center\":{\"$ref\":\"#/$defs/position\"},\"radius\":{\"type\":\"number\",\"maximum\":16},\"itemNames\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"durationMs\":{\"type\":\"integer\",\"maximum\":15000}}");
        add(
                tools,
                "place_block",
                "Place a real inventory BlockItem at exact position using a native support-face interaction. properties optionally requests real block states (axis/facing etc); checks candidate native placement state before mutation, then server ack and actual state. Does not replace solid blocks or auto-build access.",
                "{\"position\":{\"$ref\":\"#/$defs/position\"},\"itemName\":{\"type\":\"string\"},\"properties\":{\"type\":\"object\"}}");
        add(
                tools,
                "equip_item",
                "Move/select an actual inventory item. destination currently hand only; inventory swaps use native server actions and confirmation.",
                "{\"itemName\":{\"type\":\"string\"},\"destination\":{\"enum\":[\"hand\"]}}");
        add(
                tools,
                "eat",
                "Use an explicitly selected real consumable item until actual inventory consumption/food change. Does not choose a food source or craft food.",
                "{\"itemName\":{\"type\":\"string\"}}");
        add(
                tools,
                "attack_entity",
                "Send one native melee attack at a fresh loaded non-player entityId, within reach and visible. A sent attack does not prove damage or death.",
                "{\"entityId\":{\"type\":\"integer\"}}");
        add(
                tools,
                "follow_player",
                "Follow an explicitly named loaded player with native navigation for durationSeconds<=60, stopping outside range. Does not attack or infer destinations.",
                "{\"username\":{\"type\":\"string\"},\"range\":{\"type\":\"number\",\"minimum\":1,\"maximum\":8},\"durationSeconds\":{\"type\":\"number\",\"minimum\":1,\"maximum\":60}}");
        add(
                tools,
                "set_block_open",
                "Explicit single native interaction on a loaded block with an open boolean property. Verifies server acknowledgement and desired real state. No implicit opening while navigating.",
                "{\"position\":{\"$ref\":\"#/$defs/position\"},\"open\":{\"type\":\"boolean\"}}");
        add(
                tools,
                "wait",
                "Wait locally without an LLM request. durationMs<=10000; cancellation remains active.",
                "{\"durationMs\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10000}}");
        add(
                tools,
                "chat",
                "Send a short ordinary chat message (not a slash command) as the logged-in player.",
                "{\"message\":{\"type\":\"string\",\"maxLength\":256}}");
        ContainerActions.addDefinitions(tools);
        return tools;
    }

    static void add(JsonArray list, String name, String description, String properties) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        params.add("properties", JsonParser.parseString(properties));
        params.addProperty("additionalProperties", false);
        JsonObject definitions = new JsonObject();
        definitions.add(
                "position",
                JsonParser.parseString(
                        "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\",\"z\"],\"additionalProperties\":false}"));
        params.add("$defs", definitions);
        tool.add("parameters", params);
        list.add(tool);
    }
}
