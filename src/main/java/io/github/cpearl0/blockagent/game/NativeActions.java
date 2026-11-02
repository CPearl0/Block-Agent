package io.github.cpearl0.blockagent.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.cpearl0.blockagent.core.CancellationToken;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Native mechanics only; no resource, construction or survival task workflows. */
final class NativeActions {
    private NativeActions() {}

    static GameBridge.Action create(
            Minecraft mc, String name, JsonObject args, CancellationToken token) {
        return switch (name) {
            case "dig_at" -> new Mine(mc, args, token, false);
            case "collect_block" -> new Mine(mc, args, token, true);
            case "pickup_items" -> new Pickup(mc, args, token, null);
            case "place_block" -> new Place(mc, args, token);
            case "equip_item" -> new Equip(mc, args);
            case "eat" -> new Eat(mc, args);
            case "attack_entity" -> GameBridge.once(() -> attack(mc, args));
            case "follow_player" -> new Follow(mc, args, token);
            case "set_block_open" -> new Open(mc, args, token);
            case "wait" -> new Wait(args);
            case "chat" -> GameBridge.once(() -> chat(mc, args));
            default -> ContainerActions.create(mc, name, args, token);
        };
    }

    private static boolean ready(Minecraft mc) {
        return (mc.screen == null || BotControl.isControlScreen(mc))
                && mc.player.containerMenu == mc.player.inventoryMenu
                && mc.player.inventoryMenu.getCarried().isEmpty();
    }

    private static void requireReady(Minecraft mc) {
        if (!ready(mc))
            throw new IllegalStateException(
                    "Close the current screen/menu and empty its cursor before this action");
    }

    private static Set<String> names(JsonObject args) {
        Set<String> names = new LinkedHashSet<>();
        if (args.has("itemNames"))
            for (JsonElement item : args.getAsJsonArray("itemNames")) {
                ResourceLocation id = ResourceLocation.tryParse(item.getAsString());
                if (id == null)
                    throw new IllegalArgumentException("Invalid namespaced item identifier");
                names.add(id.toString());
            }
        return names;
    }

    /**
     * ServerboundPickItem asks the server to move a real inventory item; it does not predict
     * inventory locally.
     */
    private static final class Selector {
        final Minecraft mc;
        final String item;
        int ticks;
        boolean sent;

        Selector(Minecraft mc, String item) {
            this.mc = mc;
            ResourceLocation id = ResourceLocation.tryParse(item);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id))
                throw new IllegalArgumentException("Unknown client item " + item);
            this.item = id.toString();
        }

        boolean tick() {
            requireReady(mc);
            ItemStack held = mc.player.getMainHandItem();
            if (!held.isEmpty() && WorldData.name(held).equals(item)) return true;
            if (sent) {
                if (++ticks > 80)
                    throw new IllegalStateException(
                            "Server did not confirm inventory item selection");
                return false;
            }
            var inventory = mc.player.getInventory();
            for (int i = 0; i < 36; i++)
                if (!inventory.getItem(i).isEmpty()
                        && WorldData.name(inventory.getItem(i)).equals(item)) {
                    if (i < 9) {
                        inventory.selected = i;
                        return true;
                    }
                    mc.gameMode.handlePickItem(i);
                    sent = true;
                    return false;
                }
            throw new IllegalStateException("Inventory has no " + item);
        }

        void reset() {
            sent = false;
            ticks = 0;
        }
    }

    private static final class Equip implements GameBridge.Action {
        final Minecraft mc;
        final Selector selector;

        Equip(Minecraft mc, JsonObject args) {
            this.mc = mc;
            if (!WorldData.string(args, "destination", "hand").equals("hand"))
                throw new IllegalArgumentException(
                        "Only hand equipment is supported by this action");
            selector = new Selector(mc, WorldData.string(args, "itemName", ""));
        }

        public JsonObject tick() {
            if (!selector.tick()) return null;
            return GameBridge.ok(
                    "Selected real inventory item", WorldData.item(mc.player.getMainHandItem()));
        }
    }

    private static final class Wait implements GameBridge.Action {
        final long until;

        Wait(JsonObject args) {
            until =
                    System.nanoTime()
                            + WorldData.integer(args, "durationMs", 250, 1, 10_000) * 1_000_000L;
        }

        public JsonObject tick() {
            return System.nanoTime() >= until ? GameBridge.ok("Wait completed", null) : null;
        }
    }

    private static JsonObject chat(Minecraft mc, JsonObject args) {
        String text = WorldData.string(args, "message", "");
        if (text.isBlank() || text.length() > 256 || text.startsWith("/"))
            throw new IllegalArgumentException("Expected ordinary chat text, 1..256 characters");
        mc.player.connection.sendChat(text);
        return GameBridge.ok("Chat sent", null);
    }

    private static final class Mine implements GameBridge.Action {
        final Minecraft mc;
        final Object world, player;
        final CancellationToken token;
        final BlockPos position;
        final boolean collect;
        final JsonObject args;
        final Map<String, Integer> before;
        final BlockState original;
        final DropAssociation association;
        Selector selector;
        Navigation.Controller navigation;
        Pickup pickup;
        boolean started, predictedRemoval;
        int sequence = -1, ticksAfterRemoval;

        Mine(Minecraft mc, JsonObject args, CancellationToken token, boolean collect) {
            this.mc = mc;
            this.world = mc.level;
            this.player = mc.player;
            this.args = args;
            this.token = token;
            this.collect = collect;
            requireReady(mc);
            if (args.has("position")) position = WorldData.position(args, "position");
            else {
                JsonObject query = args.deepCopy();
                if (!query.has("name"))
                    throw new IllegalArgumentException("collect_block needs position or name");
                query.addProperty("count", 1);
                JsonArray found = WorldData.findBlocks(mc, query).getAsJsonArray("blocks");
                if (found.isEmpty())
                    throw new IllegalStateException("No matching loaded block found");
                position = WorldData.position(found.get(0).getAsJsonObject(), "position");
            }
            WorldData.requireLoaded(mc, position);
            original = mc.level.getBlockState(position);
            if (original.isAir() || original.getDestroySpeed(mc.level, position) < 0)
                throw new IllegalArgumentException("Target is air or unbreakable");
            if (args.has("name") && !WorldData.matches(original, args.get("name").getAsString()))
                throw new IllegalArgumentException(
                        "Exact target no longer matches requested block");
            if (mc.player.blockPosition().distSqr(position) > 64 * 64)
                throw new IllegalArgumentException("Target exceeds local interaction range");
            association = collect && names(args).isEmpty() ? new DropAssociation(position) : null;
            before = WorldData.inventory(mc);
            String tool = WorldData.string(args, "toolName", "");
            if (tool.isEmpty()) {
                float speed = mc.player.getMainHandItem().getDestroySpeed(original);
                boolean valid =
                        !original.requiresCorrectToolForDrops()
                                || mc.player.getMainHandItem().isCorrectToolForDrops(original);
                for (int i = 0; i < 36; i++) {
                    ItemStack stack = mc.player.getInventory().getItem(i);
                    if (stack.isEmpty()
                            || (stack.isDamageableItem()
                                    && stack.getMaxDamage() - stack.getDamageValue() < 2)) continue;
                    boolean correct =
                            !original.requiresCorrectToolForDrops()
                                    || stack.isCorrectToolForDrops(original);
                    float candidate = stack.getDestroySpeed(original);
                    if (correct && (!valid || candidate > speed)) {
                        tool = WorldData.name(stack);
                        speed = candidate;
                        valid = true;
                    }
                }
                if (!valid)
                    throw new IllegalStateException(
                            "No actual inventory tool can harvest this block's drops");
            }
            if (!tool.isEmpty()) selector = new Selector(mc, tool);
        }

        public JsonObject tick() {
            token.throwIfCancelled();
            if (pickup != null) return pickup.tick();
            WorldData.requireLoaded(mc, position);
            if (started && association != null) association.observe(mc);
            if (!started && selector != null && !selector.tick()) return null;
            BlockState current = mc.level.getBlockState(position);
            if (!started) {
                if (!current.equals(original))
                    return GameBridge.failure(
                            "target_changed",
                            "Target changed before mining",
                            WorldData.block(mc, position));
                if (original.requiresCorrectToolForDrops()
                        && !mc.player.hasCorrectToolForDrops(original, mc.level, position))
                    return GameBridge.failure(
                            "harvest_tool_invalid",
                            "Held tool cannot harvest target drops",
                            WorldData.block(mc, position));
            }
            if (started && (predictedRemoval || !current.equals(original))) {
                if (!ServerEvidence.confirmed(mc.level, sequence)) return null;
                if (current.equals(original)
                        || (!current.isAir()
                                && !current.getCollisionShape(mc.level, position).isEmpty()))
                    return GameBridge.failure(
                            "mining_state_mismatch",
                            "Server target did not retain the predicted block removal",
                            WorldData.block(mc, position));
                mc.gameMode.stopDestroyBlock();
                Navigation.stop(mc);
                if (++ticksAfterRemoval < 2) return null;
                JsonObject evidence = new JsonObject();
                evidence.add("position", WorldData.xyz(position));
                evidence.addProperty("minedBlock", WorldData.name(original));
                evidence.addProperty("serverAcknowledged", true);
                if (!collect)
                    return GameBridge.ok("Target removed after server acknowledgement", evidence);
                JsonObject parameters = new JsonObject();
                parameters.add("center", WorldData.xyz(position));
                parameters.addProperty("radius", 6);
                parameters.addProperty("durationMs", 8000);
                if (args.has("itemNames")) parameters.add("itemNames", args.get("itemNames"));
                pickup = new Pickup(mc, parameters, token, before, association);
                pickup.mined = evidence;
                return null;
            }
            BlockHitResult hit =
                    WorldData.visibleHit(
                            mc,
                            position,
                            mc.player.getEyePosition(),
                            mc.player.blockInteractionRange());
            if (hit == null) {
                if (started) {
                    mc.gameMode.stopDestroyBlock();
                    return GameBridge.failure(
                            "interaction_not_visible",
                            "Lost actual reach or visibility while mining",
                            WorldData.block(mc, position));
                }
                if (navigation == null)
                    navigation =
                            new Navigation.Controller(
                                    mc,
                                    position,
                                    p ->
                                            WorldData.visibleHit(
                                                            mc,
                                                            position,
                                                            Vec3.atBottomCenterOf(p)
                                                                    .add(
                                                                            0,
                                                                            mc.player
                                                                                    .getEyeHeight(),
                                                                            0),
                                                            mc.player.blockInteractionRange() - .15)
                                                    != null);
                navigation.tick();
                return null;
            }
            Navigation.stop(mc);
            Navigation.look(mc, hit.getLocation());
            if (!started) {
                if (association != null) {
                    association.capture(mc);
                    before.clear();
                    before.putAll(WorldData.inventory(mc));
                }
                if (!mc.gameMode.startDestroyBlock(position, hit.getDirection()))
                    return GameBridge.failure(
                            "mining_rejected",
                            "Native game rejected the mining start",
                            WorldData.block(mc, position));
                started = true;
            } else mc.gameMode.continueDestroyBlock(position, hit.getDirection());
            sequence = ServerEvidence.sequence(mc.level);
            predictedRemoval = !mc.level.getBlockState(position).equals(original);
            mc.player.swing(InteractionHand.MAIN_HAND);
            return null;
        }

        @Override
        public void yieldToHost() {
            if (mc.gameMode != null && mc.level == world) mc.gameMode.stopDestroyBlock();
            if (pickup != null) return;
            BlockState current = mc.level == world ? mc.level.getBlockState(position) : original;
            if (started && (predictedRemoval || !current.equals(original))) return;
            started = false;
            navigation = null;
            if (selector != null) selector.reset();
        }

        @Override
        public void close() {
            Navigation.stop(mc);
            if (mc.level == world && mc.player == player && mc.gameMode != null)
                mc.gameMode.stopDestroyBlock();
            if (pickup != null) pickup.close();
        }
    }

    /**
     * Temporal/spatial item observations, not a server loot-table or exclusive attribution claim.
     */
    private static final class DropAssociation {
        private record Previous(String item, int count) {}

        final BlockPos source;
        final Map<UUID, Previous> baseline = new LinkedHashMap<>();
        final Set<String> itemNames = new LinkedHashSet<>();
        final Map<UUID, JsonObject> observations = new LinkedHashMap<>();
        boolean complete = true;

        DropAssociation(BlockPos source) {
            this.source = source;
        }

        private List<ItemEntity> nearby(Minecraft mc) {
            List<ItemEntity> entities =
                    mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(source).inflate(2));
            entities.removeIf(
                    entity -> entity.position().distanceToSqr(Vec3.atCenterOf(source)) > 4);
            return entities;
        }

        void capture(Minecraft mc) {
            for (ItemEntity entity :
                    mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(source).inflate(6))) {
                if (baseline.size() >= 256) {
                    complete = false;
                    break;
                }
                ItemStack stack = entity.getItem();
                baseline.put(
                        entity.getUUID(),
                        new Previous(
                                stack.isEmpty() ? "" : WorldData.name(stack), stack.getCount()));
            }
        }

        void observe(Minecraft mc) {
            if (!complete) return;
            for (ItemEntity entity : nearby(mc)) {
                ItemStack stack = entity.getItem();
                if (stack.isEmpty()) continue;
                String item = WorldData.name(stack);
                Previous previous = baseline.get(entity.getUUID());
                if (previous != null
                        && (!previous.item.equals(item) || stack.getCount() <= previous.count))
                    continue;
                if (!observations.containsKey(entity.getUUID()) && observations.size() >= 64) {
                    complete = false;
                    itemNames.clear();
                    return;
                }
                itemNames.add(item);
                JsonObject evidence = new JsonObject();
                evidence.addProperty("entityUuid", entity.getUUID().toString());
                evidence.addProperty("name", item);
                evidence.addProperty("baselineCount", previous == null ? 0 : previous.count);
                evidence.addProperty("observedCount", stack.getCount());
                evidence.add("observedPosition", WorldData.xyz(entity.position()));
                observations.put(entity.getUUID(), evidence);
            }
        }

        boolean unresolvedCandidate(ItemEntity entity) {
            return !baseline.containsKey(entity.getUUID())
                    && entity.position().distanceToSqr(Vec3.atCenterOf(source)) <= 4;
        }

        JsonObject evidence() {
            JsonObject value = new JsonObject();
            value.add("sourcePosition", WorldData.xyz(source));
            value.addProperty("associationRadius", 2);
            value.addProperty("baselineRadius", 6);
            value.addProperty("baselineEntityCount", baseline.size());
            value.addProperty("observationComplete", complete);
            JsonArray candidates = new JsonArray();
            for (JsonObject observed : observations.values()) candidates.add(observed.deepCopy());
            value.add("observedCandidates", candidates);
            JsonArray names = new JsonArray();
            for (String name : itemNames) names.add(name);
            value.add("observedItemNames", names);
            value.addProperty(
                    "scope",
                    "New item entities or same-item stack increases observed within two blocks after mining began, compared with a six-block pre-mining entity baseline. Local temporal/spatial association does not prove exclusive source attribution; unobserved or later drops remain unknown.");
            return value;
        }
    }

    private static final class Pickup implements GameBridge.Action {
        final Minecraft mc;
        final CancellationToken token;
        final BlockPos center;
        final double radius;
        final long until;
        final Set<String> filter;
        final Map<String, Integer> before;
        final DropAssociation association;
        final Set<BlockPos> attempted = new HashSet<>();
        Navigation.Controller navigation;
        int quietTicks, waitTicks;
        JsonObject mined;

        Pickup(
                Minecraft mc,
                JsonObject args,
                CancellationToken token,
                Map<String, Integer> initial) {
            this(mc, args, token, initial, null);
        }

        Pickup(
                Minecraft mc,
                JsonObject args,
                CancellationToken token,
                Map<String, Integer> initial,
                DropAssociation association) {
            this.mc = mc;
            this.token = token;
            this.association = association;
            center =
                    args.has("center")
                            ? WorldData.position(args, "center")
                            : mc.player.blockPosition();
            radius = WorldData.number(args, "radius", 6, .1, 16);
            if (center.distSqr(mc.player.blockPosition()) > 64 * 64)
                throw new IllegalArgumentException("Pickup center too far away");
            filter = association == null ? names(args) : association.itemNames;
            before = initial == null ? WorldData.inventory(mc) : initial;
            until =
                    System.nanoTime()
                            + WorldData.integer(args, "durationMs", 6000, 100, 15000) * 1_000_000L;
        }

        private List<ItemEntity> drops() {
            List<ItemEntity> items =
                    mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(radius));
            items.removeIf(
                    e ->
                            e.position().distanceToSqr(Vec3.atCenterOf(center)) > radius * radius
                                    || (!filter.isEmpty()
                                            && !e.getItem().isEmpty()
                                            && !filter.contains(WorldData.name(e.getItem())))
                                    || (association != null
                                            && filter.isEmpty()
                                            && (!e.getItem().isEmpty()
                                                    || !association.unresolvedCandidate(e))));
            items.sort(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
            return items;
        }

        public JsonObject tick() {
            token.throwIfCancelled();
            if (association != null) association.observe(mc);
            List<ItemEntity> items = drops();
            JsonObject delta =
                    association != null && filter.isEmpty()
                            ? new JsonObject()
                            : WorldData.inventoryDelta(mc, before, filter);
            int gained = 0;
            for (var entry : delta.entrySet()) gained += Math.max(0, entry.getValue().getAsInt());
            if (items.isEmpty()) {
                Navigation.stop(mc);
                if (association != null && filter.isEmpty()) {
                    quietTicks = 0;
                    if (System.nanoTime() >= until)
                        return result(false, gained, items, delta, false);
                    return null;
                }
                if (++quietTicks >= 8) return result(gained > 0, gained, items, delta, true);
                return null;
            }
            quietTicks = 0;
            if (System.nanoTime() >= until) return result(false, gained, items, delta, false);
            if (navigation != null) {
                try {
                    if (navigation.tick()) {
                        navigation = null;
                        waitTicks = 8;
                    }
                } catch (IllegalStateException failure) {
                    Navigation.stop(mc);
                    navigation = null;
                    waitTicks = 2;
                }
                return null;
            }
            if (waitTicks-- > 0) return null;
            for (ItemEntity item : items) {
                if (item.getItem().isEmpty()) continue;
                BlockPos floor = item.blockPosition();
                List<BlockPos> candidates = new ArrayList<>();
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++) {
                            BlockPos p = floor.offset(dx, dy, dz);
                            if (attempted.contains(p) || !WorldData.safe(mc, p)) continue;
                            AABB playerBox =
                                    new AABB(
                                            p.getX() + .2,
                                            p.getY(),
                                            p.getZ() + .2,
                                            p.getX() + .8,
                                            p.getY() + 1.8,
                                            p.getZ() + .8);
                            if (!playerBox.inflate(.95, .45, .95).intersects(item.getBoundingBox()))
                                continue;
                            candidates.add(p);
                        }
                candidates.sort(
                        Comparator.comparingDouble(p -> p.distToCenterSqr(mc.player.position())));
                for (BlockPos p : candidates) {
                    if (attempted.size() >= 8) break;
                    attempted.add(p);
                    try {
                        navigation = new Navigation.Controller(mc, p, 0);
                        navigation.tick();
                        return null;
                    } catch (IllegalStateException failed) {
                        navigation = null;
                        Navigation.stop(mc);
                    }
                }
            }
            return null;
        }

        private JsonObject result(
                boolean success,
                int gained,
                List<ItemEntity> items,
                JsonObject delta,
                boolean settled) {
            Navigation.stop(mc);
            JsonObject evidence = new JsonObject();
            evidence.add("center", WorldData.xyz(center));
            evidence.add("inventoryDelta", delta);
            evidence.add("actualInventoryDelta", WorldData.inventoryDelta(mc, before, Set.of()));
            evidence.addProperty("gained", gained);
            evidence.addProperty("remainingVisibleStacks", items.size());
            evidence.addProperty(
                    "pendingMetadataStacks",
                    items.stream().filter(e -> e.getItem().isEmpty()).count());
            evidence.addProperty("settled", settled);
            if (association != null) evidence.add("dropAssociation", association.evidence());
            if (mined != null) evidence.add("mined", mined);
            return success
                    ? GameBridge.ok(
                            "Pickup matching inventory gain verified and local matching drops settled; source attribution is not exclusive",
                            evidence)
                    : GameBridge.failure(
                            mined == null ? "pickup_unconfirmed" : "collection_pickup_unconfirmed",
                            "Pickup incomplete or target drop type unconfirmed; inspect actual inventory delta and local drop evidence",
                            evidence);
        }

        @Override
        public void yieldToHost() {
            navigation = null;
        }

        @Override
        public void close() {
            Navigation.stop(mc);
        }
    }

    private static final class Place implements GameBridge.Action {
        final Minecraft mc;
        final CancellationToken token;
        final BlockPos target;
        final Selector selector;
        final BlockItem item;
        final JsonObject properties;
        BlockHitResult chosen;
        int sequence = -1;
        boolean sent;
        int sneakTicks;

        Place(Minecraft mc, JsonObject args, CancellationToken token) {
            this.mc = mc;
            this.token = token;
            target = WorldData.position(args, "position");
            String itemName = WorldData.string(args, "itemName", "");
            selector = new Selector(mc, itemName);
            Item definition = BuiltInRegistries.ITEM.get(ResourceLocation.parse(selector.item));
            if (!(definition instanceof BlockItem blockItem))
                throw new IllegalArgumentException("Item is not a native BlockItem");
            item = blockItem;
            properties =
                    args.has("properties")
                            ? args.getAsJsonObject("properties").deepCopy()
                            : new JsonObject();
            // Pillar orientation is foundational placement mechanics; default to the block's own
            // default axis.
            JsonObject defaults = WorldData.properties(item.getBlock().defaultBlockState());
            if (!properties.has("axis") && defaults.has("axis"))
                properties.add("axis", defaults.get("axis"));
            for (var requested : properties.entrySet()) {
                var property = item.getBlock().getStateDefinition().getProperty(requested.getKey());
                if (property == null
                        || property.getValue(requested.getValue().getAsString()).isEmpty())
                    throw new IllegalArgumentException(
                            "Invalid requested property " + requested.getKey());
            }
        }

        public JsonObject tick() {
            token.throwIfCancelled();
            WorldData.requireLoaded(mc, target);
            if (sent) {
                if (!ServerEvidence.confirmed(mc.level, sequence)) return null;
                BlockState actual = mc.level.getBlockState(target);
                JsonObject evidence = WorldData.block(mc, target);
                evidence.addProperty("serverAcknowledged", true);
                if (actual.getBlock() != item.getBlock()
                        || !WorldData.matchesProperties(actual, properties))
                    return GameBridge.failure(
                            "placement_state_mismatch",
                            "Server block differs from requested block/properties",
                            evidence);
                return GameBridge.ok(
                        "Native placement state verified after server acknowledgement", evidence);
            }
            requireReady(mc);
            if (!selector.tick()) return null;
            BlockState existing = mc.level.getBlockState(target);
            if (!existing.canBeReplaced())
                return GameBridge.failure(
                        "placement_occupied",
                        "Target block is not replaceable",
                        WorldData.block(mc, target));
            BotControl.setKeyDown(mc.options.keyShift, true);
            if (sneakTicks++ < 2) return null;
            chosen = choosePlacement(mc, target, item, properties);
            if (chosen == null)
                return GameBridge.failure(
                        "placement_no_stance",
                        "No currently reachable visible support face produced requested native placement state; move or prepare access explicitly",
                        WorldData.block(mc, target));
            Navigation.flushLook(mc);
            var outcome = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, chosen);
            sequence = ServerEvidence.sequence(mc.level);
            sent = true;
            if (outcome.consumesAction()) mc.player.swing(InteractionHand.MAIN_HAND);
            else
                return GameBridge.failure(
                        "placement_rejected",
                        "Native item interaction did not consume the action",
                        WorldData.block(mc, target));
            return null;
        }

        @Override
        public void yieldToHost() {
            BotControl.setKeyDown(mc.options.keyShift, false);
        }

        @Override
        public void close() {
            BotControl.setKeyDown(mc.options.keyShift, false);
            Navigation.stop(mc);
        }
    }

    private static BlockHitResult choosePlacement(
            Minecraft mc, BlockPos target, BlockItem item, JsonObject properties) {
        float oldYaw = mc.player.getYRot(), oldPitch = mc.player.getXRot();
        for (Direction face : Direction.values()) {
            BlockPos support = target.relative(face.getOpposite());
            if (!WorldData.loaded(mc, support)
                    || mc.level.getBlockState(support).getShape(mc.level, support).isEmpty())
                continue;
            BlockHitResult hit =
                    WorldData.hitFace(
                            mc,
                            support,
                            face,
                            mc.player.getEyePosition(),
                            mc.player.blockInteractionRange());
            if (hit == null) continue;
            Navigation.look(mc, hit.getLocation());
            for (float yaw : new float[] {mc.player.getYRot(), 0, 90, 180, 270}) {
                mc.player.setYRot(yaw);
                BlockPlaceContext context =
                        new BlockPlaceContext(
                                mc.player,
                                InteractionHand.MAIN_HAND,
                                mc.player.getMainHandItem(),
                                hit);
                if (!context.getClickedPos().equals(target) || !context.canPlace()) continue;
                BlockState predicted = item.getBlock().getStateForPlacement(context);
                if (predicted == null
                        || !WorldData.matchesProperties(predicted, properties)
                        || !predicted.canSurvive(mc.level, target)) continue;
                return hit;
            }
        }
        mc.player.setYRot(oldYaw);
        mc.player.setXRot(oldPitch);
        return null;
    }

    private static final class Open implements GameBridge.Action {
        final Minecraft mc;
        final CancellationToken token;
        final BlockPos position;
        final boolean expected;
        int sequence = -1;
        boolean sent;

        Open(Minecraft mc, JsonObject args, CancellationToken token) {
            this.mc = mc;
            this.token = token;
            position = WorldData.position(args, "position");
            if (!args.has("open")) throw new IllegalArgumentException("Missing open");
            expected = args.get("open").getAsBoolean();
        }

        public JsonObject tick() {
            token.throwIfCancelled();
            requireReady(mc);
            WorldData.requireLoaded(mc, position);
            BlockState state = mc.level.getBlockState(position);
            JsonObject props = WorldData.properties(state);
            if (!props.has("open"))
                return GameBridge.failure(
                        "block_open_unsupported",
                        "Block has no open property",
                        WorldData.block(mc, position));
            if (sent && !ServerEvidence.confirmed(mc.level, sequence)) return null;
            if (props.get("open").getAsBoolean() == expected)
                return GameBridge.ok("Desired open state verified", WorldData.block(mc, position));
            if (sent)
                return GameBridge.failure(
                        "block_open_mismatch",
                        "Server did not retain desired open state",
                        WorldData.block(mc, position));
            BlockHitResult hit =
                    WorldData.visibleHit(
                            mc,
                            position,
                            mc.player.getEyePosition(),
                            mc.player.blockInteractionRange());
            if (hit == null)
                return GameBridge.failure(
                        "interaction_not_visible",
                        "Block is not currently reachable and visible",
                        WorldData.block(mc, position));
            Navigation.look(mc, hit.getLocation());
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            sequence = ServerEvidence.sequence(mc.level);
            sent = true;
            return null;
        }
    }

    private static final class Eat implements GameBridge.Action {
        final Minecraft mc;
        final Object world, player;
        final Selector selector;
        Map<String, Integer> before;
        int startingFood;
        boolean sent, confirming;
        int ticks;
        long contentRevision;

        Eat(Minecraft mc, JsonObject args) {
            this.mc = mc;
            world = mc.level;
            player = mc.player;
            selector = new Selector(mc, WorldData.string(args, "itemName", ""));
        }

        public JsonObject tick() {
            if (!sent) {
                if (!selector.tick()) return null;
                ItemStack stack = mc.player.getMainHandItem();
                if (stack.get(DataComponents.FOOD) == null)
                    throw new IllegalArgumentException(
                            "Selected item has no native food component");
                startingFood = mc.player.getFoodData().getFoodLevel();
                before = WorldData.inventory(mc);
                var outcome = mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                if (!outcome.consumesAction())
                    return GameBridge.failure(
                            "eat_rejected",
                            "Native game rejected use of this food in the current state",
                            null);
                BotControl.setKeyDown(mc.options.keyUse, true);
                sent = true;
                return null;
            }
            if (++ticks > 240)
                return GameBridge.failure(
                        "eat_unconfirmed", "Native item use did not confirm consumption", null);
            int oldCount = before.getOrDefault(selector.item, 0),
                    now = WorldData.inventory(mc).getOrDefault(selector.item, 0);
            if (confirming) {
                if (ServerEvidence.contentRevision(mc.level, mc.player.inventoryMenu.containerId)
                        <= contentRevision) return null;
                if (now >= oldCount)
                    return GameBridge.failure(
                            "eat_unconfirmed",
                            "Server inventory did not retain food consumption",
                            null);
                JsonObject result = new JsonObject();
                result.addProperty("itemName", selector.item);
                result.addProperty("consumed", oldCount - now);
                result.addProperty("foodBefore", startingFood);
                result.addProperty("foodAfter", mc.player.getFoodData().getFoodLevel());
                return GameBridge.ok("Food consumption confirmed against server inventory", result);
            }
            if (now < oldCount) {
                BotControl.setKeyDown(mc.options.keyUse, false);
                if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
                contentRevision =
                        ServerEvidence.contentRevision(
                                mc.level, mc.player.inventoryMenu.containerId);
                ContainerActions.requestContents(mc, mc.player.inventoryMenu);
                confirming = true;
            }
            return null;
        }

        @Override
        public void yieldToHost() {
            BotControl.setKeyDown(mc.options.keyUse, false);
            if (mc.level == world
                    && mc.player == player
                    && mc.player != null
                    && mc.player.isUsingItem()
                    && mc.gameMode != null) mc.gameMode.releaseUsingItem(mc.player);
        }

        @Override
        public void close() {
            yieldToHost();
        }
    }

    private static JsonObject attack(Minecraft mc, JsonObject args) {
        requireReady(mc);
        int id = WorldData.integer(args, "entityId", -1, 0, Integer.MAX_VALUE);
        Entity entity = mc.level.getEntity(id);
        if (entity == null || !entity.isAlive())
            return GameBridge.failure("entity_missing", "Target is no longer loaded/alive", null);
        if (entity instanceof Player)
            return GameBridge.failure(
                    "player_attack_forbidden", "This tool does not attack players", null);
        if (mc.player.distanceTo(entity) > mc.player.entityInteractionRange()
                || !mc.player.hasLineOfSight(entity))
            return GameBridge.failure(
                    "entity_not_reachable",
                    "Target is outside current melee reach or visibility",
                    null);
        Navigation.look(mc, entity.getBoundingBox().getCenter());
        mc.gameMode.attack(mc.player, entity);
        mc.player.swing(InteractionHand.MAIN_HAND);
        JsonObject evidence = new JsonObject();
        evidence.addProperty("entityId", id);
        evidence.addProperty("attackSent", true);
        return GameBridge.ok("One native melee attack sent; damage/death unconfirmed", evidence);
    }

    private static final class Follow implements GameBridge.Action {
        final Minecraft mc;
        final CancellationToken token;
        final String username;
        final double range;
        final long until;
        Navigation.Controller navigation;
        BlockPos last;
        int refresh;

        Follow(Minecraft mc, JsonObject args, CancellationToken token) {
            this.mc = mc;
            this.token = token;
            username = WorldData.string(args, "username", "");
            range = WorldData.number(args, "range", 2, 1, 8);
            until =
                    System.nanoTime()
                            + (long)
                                    (WorldData.number(args, "durationSeconds", 10, 1, 60)
                                            * 1_000_000_000L);
        }

        public JsonObject tick() {
            token.throwIfCancelled();
            if (System.nanoTime() >= until) return GameBridge.ok("Bounded follow completed", null);
            Player target =
                    mc.level.players().stream()
                            .filter(
                                    p ->
                                            p != mc.player
                                                    && p.getGameProfile()
                                                            .getName()
                                                            .equals(username))
                            .findFirst()
                            .orElse(null);
            if (target == null)
                return GameBridge.failure("player_missing", "Named player is not loaded", null);
            if (mc.player.distanceTo(target) <= range) {
                Navigation.stop(mc);
                navigation = null;
                return null;
            }
            if (navigation == null || refresh++ >= 20 || last.distSqr(target.blockPosition()) > 4) {
                last = target.blockPosition();
                navigation = new Navigation.Controller(mc, last, range);
                refresh = 0;
            }
            navigation.tick();
            return null;
        }

        @Override
        public void yieldToHost() {
            navigation = null;
        }

        @Override
        public void close() {
            Navigation.stop(mc);
        }
    }
}
