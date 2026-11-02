package io.github.cpearl0.blockagent.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

final class WorldData {
    private WorldData() {}

    static BlockPos position(JsonObject args, String key) {
        JsonObject p = args.getAsJsonObject(key);
        if (p == null) throw new IllegalArgumentException("Missing " + key);
        double x = p.get("x").getAsDouble(),
                y = p.get("y").getAsDouble(),
                z = p.get("z").getAsDouble();
        if (!Double.isFinite(x + y + z)
                || x != Math.floor(x)
                || y != Math.floor(y)
                || z != Math.floor(z)
                || Math.abs(x) > 30_000_000
                || Math.abs(z) > 30_000_000
                || Math.abs(y) > 2048)
            throw new IllegalArgumentException(
                    "Position must contain bounded integer block coordinates");
        return new BlockPos((int) x, (int) y, (int) z);
    }

    static int integer(JsonObject args, String key, int fallback, int min, int max) {
        if (!args.has(key)) return fallback;
        double value = args.get(key).getAsDouble();
        if (!Double.isFinite(value) || value != Math.floor(value) || value < min || value > max)
            throw new IllegalArgumentException(
                    "Invalid " + key + "; expected integer " + min + ".." + max);
        return (int) value;
    }

    static double number(JsonObject args, String key, double fallback, double min, double max) {
        double value = args.has(key) ? args.get(key).getAsDouble() : fallback;
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException("Invalid " + key);
        return value;
    }

    static String string(JsonObject args, String key, String fallback) {
        return args.has(key) ? args.get(key).getAsString() : fallback;
    }

    static JsonObject xyz(Vec3 p) {
        JsonObject j = new JsonObject();
        j.addProperty("x", p.x);
        j.addProperty("y", p.y);
        j.addProperty("z", p.z);
        return j;
    }

    static JsonObject xyz(BlockPos p) {
        JsonObject j = new JsonObject();
        j.addProperty("x", p.getX());
        j.addProperty("y", p.getY());
        j.addProperty("z", p.getZ());
        return j;
    }

    static String name(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    static String name(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    static boolean loaded(Minecraft mc, BlockPos p) {
        return mc.level != null
                && !mc.level.isOutsideBuildHeight(p)
                && mc.level.hasChunk(p.getX() >> 4, p.getZ() >> 4);
    }

    static void requireLoaded(Minecraft mc, BlockPos p) {
        if (!loaded(mc, p)) throw new IllegalStateException("Block is outside loaded world: " + p);
    }

    static JsonObject item(ItemStack stack) {
        JsonObject j = new JsonObject();
        j.addProperty("name", name(stack));
        j.addProperty("displayName", stack.getHoverName().getString());
        j.addProperty("count", stack.getCount());
        j.addProperty("damage", stack.getDamageValue());
        j.addProperty("maxDamage", stack.getMaxDamage());
        j.addProperty("maxStackSize", stack.getMaxStackSize());
        ItemComponents.addPreview(j, stack);
        var food = stack.get(DataComponents.FOOD);
        if (food != null) {
            JsonObject nutrition = new JsonObject();
            nutrition.addProperty("nutrition", food.nutrition());
            nutrition.addProperty("saturation", food.saturation());
            nutrition.addProperty("canAlwaysEat", food.canAlwaysEat());
            j.add("food", nutrition);
        }
        return j;
    }

    static Map<String, Integer> inventory(Minecraft mc) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty()) result.merge(name(stack), stack.getCount(), Integer::sum);
        }
        return result;
    }

    static JsonObject inventoryDelta(Minecraft mc, Map<String, Integer> before, Set<String> names) {
        Map<String, Integer> after = inventory(mc);
        JsonObject delta = new JsonObject();
        for (var entry : after.entrySet())
            if (names.isEmpty() || names.contains(entry.getKey())) {
                int difference = entry.getValue() - before.getOrDefault(entry.getKey(), 0);
                if (difference != 0) delta.addProperty(entry.getKey(), difference);
            }
        for (var entry : before.entrySet())
            if (!after.containsKey(entry.getKey())
                    && (names.isEmpty() || names.contains(entry.getKey())))
                delta.addProperty(entry.getKey(), -entry.getValue());
        return delta;
    }

    static JsonObject properties(BlockState state) {
        JsonObject j = new JsonObject();
        for (var entry : state.getValues().entrySet())
            j.addProperty(entry.getKey().getName(), propertyName(entry.getKey(), entry.getValue()));
        return j;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyName(Property property, Comparable value) {
        return property.getName(value);
    }

    static boolean matchesProperties(BlockState state, JsonObject expected) {
        if (expected == null) return true;
        JsonObject current = properties(state);
        for (var entry : expected.entrySet())
            if (!current.has(entry.getKey())
                    || !current.get(entry.getKey())
                            .getAsString()
                            .equals(entry.getValue().getAsString())) return false;
        return true;
    }

    static JsonObject block(Minecraft mc, BlockPos p) {
        requireLoaded(mc, p);
        BlockState state = mc.level.getBlockState(p);
        JsonObject j = new JsonObject();
        j.add("position", xyz(p));
        j.addProperty("name", name(state));
        j.addProperty("stateId", Block.getId(state));
        j.add("properties", properties(state));
        j.addProperty("replaceable", state.canBeReplaced());
        j.addProperty("air", state.isAir());
        j.addProperty("hardness", state.getDestroySpeed(mc.level, p));
        j.addProperty("requiresCorrectTool", state.requiresCorrectToolForDrops());
        j.addProperty("correctHeldTool", mc.player.hasCorrectToolForDrops(state, mc.level, p));
        JsonArray shape = new JsonArray();
        for (AABB box :
                state.getCollisionShape(mc.level, p, CollisionContext.of(mc.player)).toAabbs()) {
            JsonArray bounds = new JsonArray();
            bounds.add(box.minX);
            bounds.add(box.minY);
            bounds.add(box.minZ);
            bounds.add(box.maxX);
            bounds.add(box.maxY);
            bounds.add(box.maxZ);
            shape.add(bounds);
        }
        j.add("collision", shape);
        JsonArray tags = new JsonArray();
        state.getTags().limit(24).forEach(t -> tags.add(t.location().toString()));
        j.add("tags", tags);
        j.addProperty(
                "fluid",
                state.getFluidState().isEmpty()
                        ? ""
                        : BuiltInRegistries.FLUID
                                .getKey(state.getFluidState().getType())
                                .toString());
        return j;
    }

    static JsonObject snapshot(Minecraft mc) {
        JsonObject j = new JsonObject();
        j.addProperty("connected", true);
        j.addProperty("username", mc.player.getGameProfile().getName());
        j.addProperty("dimension", mc.level.dimension().location().toString());
        j.addProperty(
                "server",
                mc.getCurrentServer() == null ? "singleplayer" : mc.getCurrentServer().ip);
        j.addProperty(
                "serverId",
                mc.getCurrentServer() == null
                        ? "singleplayer:"
                                + (mc.getSingleplayerServer() == null
                                        ? "unknown"
                                        : mc.getSingleplayerServer()
                                                .getWorldPath(
                                                        net.minecraft.world.level.storage
                                                                .LevelResource.ROOT)
                                                .toAbsolutePath()
                                                .normalize()
                                                .getFileName()
                                                .toString())
                        : mc.getCurrentServer().ip);
        j.add("position", xyz(mc.player.position()));
        j.addProperty("health", mc.player.getHealth());
        j.addProperty("food", mc.player.getFoodData().getFoodLevel());
        j.addProperty("saturation", mc.player.getFoodData().getSaturationLevel());
        j.addProperty("oxygen", mc.player.getAirSupply());
        j.addProperty("onSolidGround", mc.player.onGround());
        j.addProperty("inWater", mc.player.isInWater());
        j.addProperty("onFire", mc.player.isOnFire());
        j.addProperty("gameTime", mc.level.getGameTime());
        j.addProperty("dayTime", mc.level.getDayTime());
        j.addProperty("difficulty", mc.level.getDifficulty().getKey());
        j.addProperty("selectedHotbar", mc.player.getInventory().selected);
        JsonArray inventory = new JsonArray();
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                JsonObject value = item(stack);
                value.addProperty("slot", i);
                inventory.add(value);
            }
        }
        j.add("inventory", inventory);
        JsonArray entities = new JsonArray();
        for (Entity entity :
                mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(24))) {
            if (entities.size() >= 64) break;
            JsonObject value = new JsonObject();
            value.addProperty("id", entity.getId());
            value.addProperty(
                    "name", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            value.addProperty("displayName", entity.getName().getString());
            value.add("position", xyz(entity.position()));
            value.addProperty("distance", mc.player.distanceTo(entity));
            if (entity instanceof LivingEntity living)
                value.addProperty("health", living.getHealth());
            if (entity instanceof ItemEntity dropped) value.add("item", item(dropped.getItem()));
            entities.add(value);
        }
        j.add("nearbyEntities", entities);
        JsonArray blocks = new JsonArray();
        Map<String, Integer> counts = new LinkedHashMap<>();
        BlockPos center = mc.player.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-6, -3, -6), center.offset(6, 4, 6)))
            if (loaded(mc, p)) {
                BlockState state = mc.level.getBlockState(p);
                if (!state.isAir()) counts.merge(name(state), 1, Integer::sum);
            }
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(32)
                .forEach(
                        e -> {
                            JsonObject v = new JsonObject();
                            v.addProperty("name", e.getKey());
                            v.addProperty("observedCount", e.getValue());
                            blocks.add(v);
                        });
        j.add("nearbyBlocks", blocks);
        j.addProperty("menuId", mc.player.containerMenu.containerId);
        j.addProperty("menuOpen", mc.player.containerMenu != mc.player.inventoryMenu);
        j.addProperty("screenOpen", mc.screen != null);
        j.addProperty("botControlActive", BotControl.isActive());
        j.addProperty("screenBlocksActions", mc.screen != null && !BotControl.isControlScreen(mc));
        j.add("headBlock", block(mc, mc.player.blockPosition().above()));
        return j;
    }

    static boolean matches(BlockState state, String query) {
        String key = name(state);
        if (query == null || query.isBlank()) return !state.isAir();
        if (query.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(query.substring(1));
            return id != null
                    && state.is(TagKey.create(net.minecraft.core.registries.Registries.BLOCK, id));
        }
        if (query.contains(":")) return key.equals(query);
        return BuiltInRegistries.BLOCK
                .getKey(state.getBlock())
                .getPath()
                .contains(query.toLowerCase(Locale.ROOT));
    }

    static JsonObject findBlocks(Minecraft mc, JsonObject args) {
        int radius = integer(args, "maxDistance", 16, 1, 32),
                limit = integer(args, "count", 16, 1, 64);
        String query = string(args, "name", "");
        BlockPos center = mc.player.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        int unknown = 0;
        for (BlockPos p :
                BlockPos.betweenClosed(
                        center.offset(-radius, -radius, -radius),
                        center.offset(radius, radius, radius)))
            if (p.distSqr(center) <= radius * radius) {
                if (!loaded(mc, p)) {
                    unknown++;
                    continue;
                }
                if (matches(mc.level.getBlockState(p), query)) found.add(p.immutable());
            }
        found.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(mc.player.position())));
        JsonArray entries = new JsonArray();
        for (BlockPos p : found.subList(0, Math.min(limit, found.size()))) {
            JsonObject entry = block(mc, p);
            entry.addProperty("distance", Math.sqrt(p.distToCenterSqr(mc.player.position())));
            entry.addProperty(
                    "visibleFromCurrentEye",
                    visibleHit(mc, p, mc.player.getEyePosition(), radius + 2) != null);
            JsonArray open = new JsonArray(), unknownFaces = new JsonArray();
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = p.relative(direction);
                if (!loaded(mc, neighbor)) {
                    unknownFaces.add(direction.getName());
                    continue;
                }
                BlockState adjacent = mc.level.getBlockState(neighbor);
                if (adjacent.getFluidState().isEmpty()
                        && adjacent.getCollisionShape(mc.level, neighbor).isEmpty())
                    open.add(direction.getName());
            }
            entry.add("openFaces", open);
            entry.add("unknownFaces", unknownFaces);
            entries.add(entry);
        }
        JsonObject result = new JsonObject();
        result.add("blocks", entries);
        result.addProperty("omittedMatches", Math.max(0, found.size() - limit));
        result.addProperty("unknownCells", unknown);
        return result;
    }

    static JsonObject survey(Minecraft mc, JsonObject args) {
        JsonObject bounds = args.getAsJsonObject("bounds");
        BlockPos min = position(bounds, "min"), max = position(bounds, "max");
        int sx = max.getX() - min.getX() + 1,
                sy = max.getY() - min.getY() + 1,
                sz = max.getZ() - min.getZ() + 1;
        if (sx < 1 || sy < 1 || sz < 1 || sx > 16 || sy > 16 || sz > 16 || sx * sy * sz > 4096)
            throw new IllegalArgumentException("Survey bounds exceed 16 per axis/4096 cells");
        JsonArray palette = new JsonArray(), layers = new JsonArray();
        Map<String, String> tokens = new LinkedHashMap<>();
        int unknown = 0;
        for (int y = min.getY(); y <= max.getY(); y++) {
            JsonObject layer = new JsonObject();
            layer.addProperty("y", y);
            JsonArray rows = new JsonArray(), standing = new JsonArray();
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                StringJoiner row = new StringJoiner(" ");
                StringBuilder feet = new StringBuilder();
                for (int x = min.getX(); x <= max.getX(); x++) {
                    BlockPos p = new BlockPos(x, y, z);
                    JsonObject descriptor = new JsonObject();
                    if (!loaded(mc, p)) {
                        descriptor.add("name", JsonNull.INSTANCE);
                        descriptor.addProperty("collision", "unknown");
                        unknown++;
                    } else {
                        BlockState state = mc.level.getBlockState(p);
                        descriptor.addProperty("name", name(state));
                        descriptor.add("properties", properties(state));
                        var shape =
                                state.getCollisionShape(
                                        mc.level, p, CollisionContext.of(mc.player));
                        List<AABB> boxes = shape.toAabbs();
                        boolean full =
                                boxes.size() == 1
                                        && boxes.getFirst().equals(new AABB(0, 0, 0, 1, 1, 1));
                        descriptor.addProperty(
                                "collision",
                                shape.isEmpty() ? "empty" : full ? "full_cube" : "partial");
                        descriptor.addProperty("fullCubeSupport", full);
                        descriptor.addProperty("blockEntity", state.hasBlockEntity());
                        descriptor.addProperty(
                                "fluid",
                                state.getFluidState().isEmpty()
                                        ? ""
                                        : BuiltInRegistries.FLUID
                                                .getKey(state.getFluidState().getType())
                                                .toString());
                        JsonArray collision = new JsonArray();
                        for (AABB box : boxes) {
                            JsonArray coordinates = new JsonArray();
                            for (double v :
                                    new double[] {
                                        box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ
                                    }) coordinates.add(v);
                            collision.add(coordinates);
                        }
                        descriptor.add("collisionBoxes", collision);
                    }
                    String signature = descriptor.toString(), token = tokens.get(signature);
                    if (token == null) {
                        token = Integer.toString(tokens.size(), 36);
                        tokens.put(signature, token);
                        descriptor.addProperty("token", token);
                        palette.add(descriptor);
                    }
                    row.add(token);
                    feet.append(
                            y == min.getY()
                                            || y == max.getY()
                                            || !loaded(mc, p)
                                            || !loaded(mc, p.above())
                                            || !loaded(mc, p.below())
                                    ? '?'
                                    : safe(mc, p) ? 'C' : 'B');
                }
                rows.add(row.toString());
                standing.add(feet.toString());
            }
            layer.add("blocks", rows);
            layer.add("standing", standing);
            layers.add(layer);
        }
        JsonObject result = new JsonObject();
        result.add("bounds", bounds.deepCopy());
        JsonObject size = new JsonObject();
        size.addProperty("x", sx);
        size.addProperty("y", sy);
        size.addProperty("z", sz);
        result.add("size", size);
        result.add("palette", palette);
        result.add("layers", layers);
        result.addProperty("scannedCells", sx * sy * sz);
        result.addProperty("loadedCells", sx * sy * sz - unknown);
        result.addProperty("unknownCells", unknown);
        result.addProperty("completeGeometry", true);
        result.add("omittedLayers", new JsonArray());
        result.addProperty(
                "coordinates",
                "Inclusive integer bounds. Layers ascend +y; rows advance +z from min.z; space-separated base36 palette tokens advance +x from min.x. Standing C=locally certified dry support/body clearance, B=not certified, ?=unknown or boundary support/head. No path, ownership, placement or layout is inferred. completeGeometry describes returned layers; unknownCells still matter.");
        return result;
    }

    static JsonObject registry(Minecraft mc, JsonObject args) {
        String kind = string(args, "kind", "blocks"),
                query = string(args, "query", "").toLowerCase(Locale.ROOT);
        int limit = integer(args, "count", 32, 1, 64);
        JsonArray values = new JsonArray();
        int matches = 0;
        if (kind.equals("recipes")) {
            for (RecipeHolder<?> holder : mc.level.getRecipeManager().getRecipes()) {
                String id = holder.id().toString();
                ItemStack output = holder.value().getResultItem(mc.level.registryAccess());
                if (!id.contains(query) && !name(output).contains(query)) continue;
                matches++;
                if (values.size() >= limit) continue;
                JsonObject value = new JsonObject();
                value.addProperty("id", id);
                value.addProperty(
                        "type",
                        BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()).toString());
                value.add("output", item(output));
                JsonArray ingredients = new JsonArray();
                for (var ingredient : holder.value().getIngredients()) {
                    JsonArray alternatives = new JsonArray();
                    for (ItemStack stack : ingredient.getItems()) {
                        if (alternatives.size() >= 32) break;
                        alternatives.add(item(stack));
                    }
                    ingredients.add(alternatives);
                }
                value.add("ingredients", ingredients);
                values.add(value);
            }
        } else if (kind.equals("items")) {
            for (Item item : BuiltInRegistries.ITEM) {
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                if (!id.contains(query)
                        && !item.getDescription()
                                .getString()
                                .toLowerCase(Locale.ROOT)
                                .contains(query)) continue;
                matches++;
                if (values.size() >= limit) continue;
                JsonObject value = item(item.getDefaultInstance());
                JsonArray tags = new JsonArray();
                BuiltInRegistries.ITEM
                        .wrapAsHolder(item)
                        .tags()
                        .limit(24)
                        .forEach(tag -> tags.add(tag.location().toString()));
                value.add("tags", tags);
                value.addProperty("blockItem", item instanceof BlockItem);
                values.add(value);
            }
        } else if (kind.equals("blocks")) {
            for (Block block : BuiltInRegistries.BLOCK) {
                String id = BuiltInRegistries.BLOCK.getKey(block).toString();
                if (!id.contains(query)
                        && !block.getName().getString().toLowerCase(Locale.ROOT).contains(query))
                    continue;
                matches++;
                if (values.size() >= limit) continue;
                JsonObject value = new JsonObject();
                value.addProperty("name", id);
                value.addProperty("displayName", block.getName().getString());
                value.addProperty(
                        "itemName", BuiltInRegistries.ITEM.getKey(block.asItem()).toString());
                value.add("defaultProperties", properties(block.defaultBlockState()));
                JsonObject choices = new JsonObject();
                for (Property<?> property : block.getStateDefinition().getProperties()) {
                    JsonArray options = new JsonArray();
                    for (Comparable<?> option : property.getPossibleValues())
                        options.add(propertyName(property, option));
                    choices.add(property.getName(), options);
                }
                value.add("propertyValues", choices);
                values.add(value);
            }
        } else throw new IllegalArgumentException("Unknown registry kind");
        JsonObject result = new JsonObject();
        result.add("entries", values);
        result.addProperty("omittedMatches", Math.max(0, matches - values.size()));
        return result;
    }

    static boolean safe(Minecraft mc, BlockPos p) {
        if (!loaded(mc, p) || !loaded(mc, p.above()) || !loaded(mc, p.below())) return false;
        for (BlockPos q : new BlockPos[] {p, p.above(), p.below()}) {
            BlockState state = mc.level.getBlockState(q);
            if (!state.getFluidState().isEmpty()
                    || state.is(BlockTags.FIRE)
                    || state.is(Blocks.CACTUS)
                    || state.is(Blocks.MAGMA_BLOCK)
                    || state.is(Blocks.CAMPFIRE)
                    || state.is(Blocks.SOUL_CAMPFIRE)
                    || state.is(Blocks.SWEET_BERRY_BUSH)
                    || state.is(Blocks.POWDER_SNOW)) return false;
        }
        var shape = mc.level.getBlockState(p.below()).getCollisionShape(mc.level, p.below());
        if (shape.isEmpty() || Math.abs(shape.max(Direction.Axis.Y) - 1) > 0.001) return false;
        double half = mc.player.getBbWidth() / 2d;
        AABB body =
                new AABB(
                        p.getX() + .5 - half,
                        p.getY() + .001,
                        p.getZ() + .5 - half,
                        p.getX() + .5 + half,
                        p.getY() + mc.player.getBbHeight(),
                        p.getZ() + .5 + half);
        AABB feet = new AABB(body.minX, p.getY() - .01, body.minZ, body.maxX, p.getY(), body.maxZ);
        if (shape.toAabbs().stream().noneMatch(box -> box.move(p.below()).intersects(feet)))
            return false;
        return mc.level.noCollision(mc.player, body);
    }

    static JsonObject candidates(Minecraft mc, JsonObject args, boolean placements) {
        int radius = integer(args, "radius", 6, 1, 16), limit = integer(args, "count", 16, 1, 64);
        BlockPos center = mc.player.blockPosition();
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos p :
                BlockPos.betweenClosed(
                        center.offset(-radius, -3, -radius), center.offset(radius, 3, radius))) {
            if (!loaded(mc, p)) continue;
            if (placements) {
                if (!mc.level.getBlockState(p).canBeReplaced()) continue;
                boolean support = false;
                for (Direction direction : Direction.values()) {
                    BlockPos other = p.relative(direction);
                    if (loaded(mc, other)
                            && !mc.level
                                    .getBlockState(other)
                                    .getCollisionShape(mc.level, other)
                                    .isEmpty()) {
                        support = true;
                        break;
                    }
                }
                if (!support) continue;
            } else if (!safe(mc, p)) continue;
            positions.add(p.immutable());
        }
        positions.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(mc.player.position())));
        JsonArray values = new JsonArray();
        for (BlockPos p : positions.subList(0, Math.min(limit, positions.size())))
            values.add(xyz(p));
        JsonObject result = new JsonObject();
        result.add("positions", values);
        result.addProperty("omitted", Math.max(0, positions.size() - limit));
        return result;
    }

    static BlockHitResult visibleHit(Minecraft mc, BlockPos target, Vec3 eye, double reach) {
        requireLoaded(mc, target);
        var shape = mc.level.getBlockState(target).getShape(mc.level, target);
        if (shape.isEmpty()) return null;
        for (AABB box : shape.toAabbs())
            for (Direction face : Direction.values()) {
                Vec3 point =
                        new Vec3(
                                target.getX() + (box.minX + box.maxX) / 2,
                                target.getY() + (box.minY + box.maxY) / 2,
                                target.getZ() + (box.minZ + box.maxZ) / 2);
                point =
                        point.with(
                                face.getAxis(),
                                switch (face.getAxis()) {
                                    case X ->
                                            target.getX()
                                                    + (face == Direction.WEST
                                                            ? box.minX
                                                            : box.maxX);
                                    case Y ->
                                            target.getY()
                                                    + (face == Direction.DOWN
                                                            ? box.minY
                                                            : box.maxY);
                                    case Z ->
                                            target.getZ()
                                                    + (face == Direction.NORTH
                                                            ? box.minZ
                                                            : box.maxZ);
                                });
                if (eye.distanceToSqr(point) > reach * reach) continue;
                Vec3 through = point.add(point.subtract(eye).normalize().scale(.005));
                BlockHitResult hit =
                        mc.level.clip(
                                new ClipContext(
                                        eye,
                                        through,
                                        ClipContext.Block.OUTLINE,
                                        ClipContext.Fluid.NONE,
                                        mc.player));
                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target))
                    return hit;
            }
        return null;
    }

    static BlockHitResult hitFace(
            Minecraft mc, BlockPos target, Direction face, Vec3 eye, double reach) {
        requireLoaded(mc, target);
        var shape = mc.level.getBlockState(target).getShape(mc.level, target);
        for (AABB box : shape.toAabbs())
            for (double first : new double[] {.5, .2, .8})
                for (double second : new double[] {.5, .2, .8}) {
                    double x = box.minX + (box.maxX - box.minX) * first,
                            y = box.minY + (box.maxY - box.minY) * second,
                            z = box.minZ + (box.maxZ - box.minZ) * first;
                    switch (face) {
                        case WEST -> x = box.minX;
                        case EAST -> x = box.maxX;
                        case DOWN -> y = box.minY;
                        case UP -> y = box.maxY;
                        case NORTH -> z = box.minZ;
                        case SOUTH -> z = box.maxZ;
                    }
                    Vec3 point = new Vec3(target.getX() + x, target.getY() + y, target.getZ() + z);
                    if (eye.distanceToSqr(point) > reach * reach) continue;
                    Vec3 through =
                            point.add(
                                    face.getStepX() * -.002,
                                    face.getStepY() * -.002,
                                    face.getStepZ() * -.002);
                    BlockHitResult hit =
                            mc.level.clip(
                                    new ClipContext(
                                            eye,
                                            through,
                                            ClipContext.Block.OUTLINE,
                                            ClipContext.Fluid.NONE,
                                            mc.player));
                    if (hit.getType() == HitResult.Type.BLOCK
                            && hit.getBlockPos().equals(target)
                            && hit.getDirection() == face) return hit;
                }
        return null;
    }
}
