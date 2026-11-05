package io.github.cpearl0.blockagent.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.cpearl0.blockagent.core.CancellationToken;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;

/** Native menu mechanics. Slot meaning and machine workflows remain model decisions. */
final class ContainerActions {
    private static final Map<AbstractContainerMenu, BlockPos> ORIGINS = new WeakHashMap<>();

    private ContainerActions() {}

    static GameBridge.Action create(
            Minecraft mc, String name, JsonObject args, CancellationToken token) {
        return switch (name) {
            case "inspect_inventory_window" -> new Inspect(mc, args, token);
            case "transfer_inventory_slot" -> new Transfer(mc, args, token);
            case "craft_item" -> new Craft(mc, args, token);
            case "smelt_item" -> new Smelt(mc, args, token);
            case "close_inventory_window" -> new Close(mc, args, token);
            default ->
                    GameBridge.once(
                            () ->
                                    GameBridge.failure(
                                            "unknown_tool",
                                            "Unknown native game tool: " + name,
                                            null));
        };
    }

    static void addDefinitions(JsonArray tools) {
        ToolDefinitions.add(
                tools,
                "inspect_inventory_window",
                "Inspect the current native menu, or explicitly interact once with a visible reachable block at position using an empty main hand. A client-side menu provider is not required: accept only an actual newly opened server menu and complete server content synchronization. Does not navigate. An interaction that opens no menu fails and may have native world effects. Returns real windowId/stateId, cursor and slots, bounded nondefault component previews, verified player/container regions (unknown for opaque handlers), native slot/handler classes and item-dependent insertion rules. Optional probeItemName tests mayPlace for that item. Leaves the menu open; close_inventory_window before moving. Unknown machine slot semantics remain unknown.",
                "{\"position\":{\"$ref\":\"#/$defs/position\"},\"probeItemName\":{\"type\":\"string\"}}");
        ToolDefinitions.add(
                tools,
                "transfer_inventory_slot",
                "Move count 1..64 of exact itemName from sourceSlot to destinationSlot in the CURRENT menu using native clicks. REQUIRED windowId and stateId must come from a fresh inspect/transfer receipt. Preserves actual stack components and confirms exact source/destination counts against complete server contents. Destination must accept the entire requested count; no item swapping or guessed machine workflow. Partial extraction requires a source that accepts its remainder; result slots may require whole-stack extraction. Cursor must start empty. Leaves the menu open.",
                "{\"windowId\":{\"type\":\"integer\"},\"stateId\":{\"type\":\"integer\"},\"sourceSlot\":{\"type\":\"integer\",\"minimum\":0},\"destinationSlot\":{\"type\":\"integer\",\"minimum\":0},\"itemName\":{\"type\":\"string\"},\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":64},\"position\":{\"$ref\":\"#/$defs/position\"}}");
        ToolDefinitions.add(
                tools,
                "craft_item",
                "Craft at least count additional units of itemName using an actual synchronized, unlocked ordinary crafting recipe and native recipe-book placement/result clicks. Optional recipeId chooses an exact recipe. Uses current player 2x2 or crafting-table 3x3 menu; position may explicitly open a reachable table. Requires initially empty cursor/grid, available ingredients and output/remainder capacity. Confirms real output inventory growth, returns grid remainders safely, and leaves menu open. No resource gathering, smelting, custom machine processing or special/dynamic recipes. Recipe batch yield may exceed count.",
                "{\"itemName\":{\"type\":\"string\"},\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":64},\"recipeId\":{\"type\":\"string\"},\"position\":{\"$ref\":\"#/$defs/position\"}}");
        ToolDefinitions.add(
                tools,
                "smelt_item",
                "Produce at least count additional itemName using native furnace/blast-furnace/smoker clicks and synchronized cooking recipes. Optional fuelItemName selects fuel; otherwise chooses a compatible inventory fuel. Optional position opens a visible reachable furnace. Requires empty cursor, input and output slots; existing fuel is preserved. Feeds one ingredient at a time, waits up to 45 seconds per batch, confirms server output and actual inventory growth. Leaves menu open; failures/cancellation can leave ingredients/fuel in the furnace. No gathering, cheats or commands. Batch yield may exceed count; allow sufficient tool timeout (roughly 15 seconds per ordinary furnace item).",
                "{\"itemName\":{\"type\":\"string\"},\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":64},\"fuelItemName\":{\"type\":\"string\"},\"position\":{\"$ref\":\"#/$defs/position\"}}");
        ToolDefinitions.add(
                tools,
                "close_inventory_window",
                "Close the current native inventory/container screen and wait for a server inventory synchronization. Requires empty cursor and empty player/table crafting grid, preventing implicit dropped crafting materials. Other menu contents are left to their native server semantics.",
                "{}");
    }

    private static final class Fault extends RuntimeException {
        final String code;

        Fault(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private abstract static class Action implements GameBridge.Action {
        final Minecraft mc;
        final LocalPlayer player;
        final ClientLevel level;
        final CancellationToken token;
        ItemStack ownedCursor = ItemStack.EMPTY;
        AbstractContainerMenu ownedMenu;
        int recoverySlot = -1;

        Action(Minecraft mc, CancellationToken token) {
            this.mc = mc;
            this.player = mc.player;
            this.level = mc.level;
            this.token = token;
        }

        abstract JsonObject step();

        @Override
        public final JsonObject tick() {
            token.throwIfCancelled();
            if (mc.player != player || mc.level != level)
                throw new java.util.concurrent.CancellationException(
                        "Menu action world/player changed");
            try {
                return step();
            } catch (Fault error) {
                JsonObject data = snapshot(mc, player.containerMenu, null);
                data.addProperty(
                        "cleanupMayBePending", !player.containerMenu.getCarried().isEmpty());
                return GameBridge.failure(error.code, error.getMessage(), data);
            }
        }

        void click(Session session, int index, int button, ClickType type) {
            token.throwIfCancelled();
            session.checkIdentity();
            mc.gameMode.handleInventoryMouseClick(
                    session.menu.containerId, index, button, type, player);
            session.synchronize();
        }

        @Override
        public void close() {
            // Never send an old menu interaction into a new player/world. Cleanup never drops the
            // cursor.
            if (mc.player != player
                    || mc.level != level
                    || mc.getConnection() == null
                    || !player.isAlive()
                    || ownedMenu == null
                    || player.containerMenu != ownedMenu) return;
            ItemStack carried = ownedMenu.getCarried();
            if (carried.isEmpty() || ownedCursor.isEmpty() || !same(carried, ownedCursor)) return;
            int target = recoverySlot;
            if (target < 0
                    || target >= ownedMenu.slots.size()
                    || !accepts(ownedMenu.getSlot(target), carried, carried.getCount()))
                target = inventoryDestination(mc, ownedMenu, carried);
            if (target >= 0) {
                try {
                    mc.gameMode.handleInventoryMouseClick(
                            ownedMenu.containerId, target, 0, ClickType.PICKUP, player);
                    requestContents(mc, ownedMenu);
                } catch (RuntimeException ignored) {
                    /* Preserve the menu/cursor if native cleanup cannot be sent. */
                }
            }
            // If no safe destination exists, retain the visible cursor/menu for explicit recovery.
        }
    }

    private static final class Session {
        final Minecraft mc;
        final ClientLevel level;
        final LocalPlayer player;
        final BlockPos position;
        AbstractContainerMenu menu;
        boolean opening, synchronizing, selectingEmptyHand;
        long since, contentRevision;

        Session(Minecraft mc, JsonObject args) {
            this.mc = mc;
            level = mc.level;
            player = mc.player;
            position = args.has("position") ? WorldData.position(args, "position") : null;
        }

        boolean ready() {
            if (mc.player != player || mc.level != level)
                throw new java.util.concurrent.CancellationException("Menu session changed");
            if (menu != null) {
                checkIdentity();
                if (!synchronizing) return true;
                if (ServerEvidence.contentRevision(level, menu.containerId) > contentRevision) {
                    synchronizing = false;
                    return true;
                }
                if (System.nanoTime() - since > 5_000_000_000L)
                    throw new Fault(
                            "inventory_sync_unconfirmed",
                            "No complete server contents arrived within five seconds; predicted slots are not accepted");
                return false;
            }
            if (opening) {
                if (player.containerMenu != player.inventoryMenu) {
                    menu = player.containerMenu;
                    ORIGINS.put(menu, position);
                    synchronize();
                    return false;
                }
                if (System.nanoTime() - since > 5_000_000_000L)
                    throw new Fault(
                            "inventory_window_not_opened",
                            "The native block interaction did not open a menu");
                return false;
            }
            if (position == null || position.equals(ORIGINS.get(player.containerMenu))) {
                menu = player.containerMenu;
                synchronize();
                return false;
            }
            if (player.containerMenu != player.inventoryMenu
                    || mc.screen != null && !BotControl.isControlScreen(mc))
                throw new Fault(
                        "inventory_window_busy",
                        "Close the current menu/screen before opening a different block");
            if (!player.inventoryMenu.getCarried().isEmpty())
                throw new Fault(
                        "inventory_cursor_not_empty",
                        "Resolve the existing cursor before opening a block");
            WorldData.requireLoaded(mc, position);
            // Mod blocks may open their BlockEntity's menu only in server-side useWithoutItem.
            // Client getMenuProvider()==null is therefore not proof that this interaction has no
            // menu.
            var hit =
                    WorldData.visibleHit(
                            mc, position, player.getEyePosition(), player.blockInteractionRange());
            if (hit == null)
                throw new Fault(
                        "interaction_not_visible",
                        "Menu block is not visibly within native interaction reach");
            // Default block interaction (chests/tables etc.) runs only for MAIN_HAND in 1.21.1.
            if (!player.getMainHandItem().isEmpty()) {
                if (selectingEmptyHand) {
                    if (System.nanoTime() - since > 3_000_000_000L)
                        throw new Fault(
                                "inventory_empty_hand_unconfirmed",
                                "Server did not select the requested empty inventory slot");
                    return false;
                }
                for (int i = 0; i < 9; i++)
                    if (player.getInventory().getItem(i).isEmpty()) {
                        player.getInventory().selected = i;
                        break;
                    }
                if (!player.getMainHandItem().isEmpty()) {
                    for (int i = 9; i < 36; i++)
                        if (player.getInventory().getItem(i).isEmpty()) {
                            mc.gameMode.handlePickItem(i);
                            selectingEmptyHand = true;
                            since = System.nanoTime();
                            return false;
                        }
                    throw new Fault(
                            "inventory_empty_hand_required",
                            "Opening an unknown menu requires an empty main-hand slot; inventory is full");
                }
            }
            if (player.isSecondaryUseActive())
                throw new Fault(
                        "inventory_secondary_use_active",
                        "Release secondary-use/sneaking before native menu interaction");
            Navigation.look(mc, hit.getLocation());
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            opening = true;
            since = System.nanoTime();
            return false;
        }

        void checkIdentity() {
            if (mc.player != player || mc.level != level || player.containerMenu != menu)
                throw new Fault(
                        "inventory_window_changed", "The original menu is no longer current");
        }

        void synchronize() {
            checkIdentity();
            contentRevision = ServerEvidence.contentRevision(level, menu.containerId);
            since = System.nanoTime();
            synchronizing = true;
            requestContents(mc, menu);
        }
    }

    static void requestContents(Minecraft mc, AbstractContainerMenu menu) {
        // Vanilla accepts slot -1 as a no-op. A deliberately stale state requests full
        // authoritative contents.
        // Unlike -999, slot -1 never drops a carried stack. No local slot or state is changed here.
        mc.getConnection()
                .send(
                        new ServerboundContainerClickPacket(
                                menu.containerId,
                                -1,
                                -1,
                                0,
                                ClickType.PICKUP,
                                menu.getCarried().copy(),
                                new Int2ObjectOpenHashMap<>()));
    }

    private static JsonObject snapshot(Minecraft mc, AbstractContainerMenu menu, ItemStack probe) {
        JsonObject data = new JsonObject();
        data.addProperty("windowId", menu.containerId);
        data.addProperty("stateId", menu.getStateId());
        String type =
                menu instanceof InventoryMenu
                        ? "minecraft:inventory"
                        : BuiltInRegistries.MENU.getKey(menu.getType()).toString();
        data.addProperty("menuType", type);
        data.addProperty("menuClass", menu.getClass().getName());
        data.addProperty("slotCount", menu.slots.size());
        data.add("cursor", WorldData.item(menu.getCarried()));
        BlockPos origin = ORIGINS.get(menu);
        if (origin != null) data.add("position", WorldData.xyz(origin));
        JsonArray slots = new JsonArray();
        for (int i = 0; i < Math.min(menu.slots.size(), 512); i++) {
            Slot slot = menu.getSlot(i);
            JsonObject entry = new JsonObject();
            SlotBacking backing = slotBacking(slot);
            entry.addProperty("index", i);
            entry.addProperty("containerSlot", slot.getContainerSlot());
            entry.addProperty(
                    "region",
                    backing.container() == null
                            ? "unknown"
                            : backing.isPlayer(mc) ? "player" : "container");
            entry.addProperty("slotClass", slot.getClass().getName());
            entry.addProperty("containerClass", slot.container.getClass().getName());
            if (slot instanceof SlotItemHandler wrapped)
                entry.addProperty(
                        "itemHandlerClass", wrapped.getItemHandler().getClass().getName());
            if (backing.isPlayer(mc)) entry.addProperty("playerInventorySlot", backing.index());
            entry.addProperty("active", slot.isActive());
            entry.addProperty("mayPickup", slot.mayPickup(mc.player));
            entry.addProperty("maxStackSize", slot.getMaxStackSize());
            entry.add("item", WorldData.item(slot.getItem()));
            entry.addProperty("insertionRule", "item-dependent");
            if (probe != null) {
                entry.addProperty("acceptsProbeItem", slot.mayPlace(probe));
                entry.addProperty("probeMaxStackSize", slot.getMaxStackSize(probe));
            }
            slots.add(entry);
        }
        data.add("slots", slots);
        data.addProperty("omittedSlots", Math.max(0, menu.slots.size() - slots.size()));
        data.addProperty("synchronizedAtMs", System.currentTimeMillis());
        data.addProperty(
                "scope",
                "Client-visible native slot rules; machine process semantics and server-hidden rules remain unknown");
        return data;
    }

    private static final class Inspect extends Action {
        final Session session;
        final ItemStack probe;

        Inspect(Minecraft mc, JsonObject args, CancellationToken token) {
            super(mc, token);
            session = new Session(mc, args);
            probe = args.has("probeItemName") ? item(args, "probeItemName") : null;
        }

        @Override
        JsonObject step() {
            return session.ready()
                    ? GameBridge.ok(
                            "Authoritative native menu contents", snapshot(mc, session.menu, probe))
                    : null;
        }
    }

    private static final class Transfer extends Action {
        final JsonObject args;
        final Session session;
        int stage, sourceIndex, destinationIndex, count, deposited;
        ItemStack sourceBefore, destinationBefore;

        Transfer(Minecraft mc, JsonObject args, CancellationToken token) {
            super(mc, token);
            this.args = args;
            session = new Session(mc, new JsonObject());
        }

        @Override
        JsonObject step() {
            if (stage == 0) {
                AbstractContainerMenu menu = player.containerMenu;
                if (!args.has("windowId") || !args.has("stateId"))
                    throw new Fault(
                            "inventory_fresh_receipt_required",
                            "Supply windowId and stateId from a fresh menu receipt");
                if (WorldData.integer(args, "windowId", -1, 0, 255) != menu.containerId
                        || WorldData.integer(args, "stateId", -1, 0, 32767) != menu.getStateId())
                    throw new Fault(
                            "inventory_stale_receipt",
                            "Menu identity/state changed since the supplied observation");
                if (args.has("position")
                        && !WorldData.position(args, "position").equals(ORIGINS.get(menu)))
                    throw new Fault(
                            "inventory_window_origin_unknown",
                            "Current menu is not the freshly inspected block position");
                requireEmptyCursor(menu);
                sourceIndex = WorldData.integer(args, "sourceSlot", -1, 0, menu.slots.size() - 1);
                destinationIndex =
                        WorldData.integer(args, "destinationSlot", -1, 0, menu.slots.size() - 1);
                if (sourceIndex < 0 || destinationIndex < 0 || sourceIndex == destinationIndex)
                    throw new Fault(
                            "inventory_invalid_slots",
                            "Specify two distinct observed slot indices");
                Slot source = menu.getSlot(sourceIndex),
                        destination = menu.getSlot(destinationIndex);
                if (slotBacking(source).aliases(slotBacking(destination)))
                    throw new Fault(
                            "inventory_slot_alias",
                            "Source and destination refer to the same native slot");
                sourceBefore = source.getItem().copy();
                destinationBefore = destination.getItem().copy();
                count = WorldData.integer(args, "count", 1, 1, 64);
                if (sourceBefore.isEmpty()
                        || !WorldData.name(sourceBefore)
                                .equals(WorldData.name(item(args, "itemName")))
                        || sourceBefore.getCount() < count)
                    throw new Fault(
                            "inventory_source_changed",
                            "Source does not hold the requested item count");
                if (!source.isActive() || !source.mayPickup(player) || source instanceof ResultSlot)
                    throw new Fault(
                            "inventory_source_not_transferable",
                            "Source cannot be picked up by ordinary transfer; crafting result requires craft_item");
                if (!accepts(destination, sourceBefore, count))
                    throw new Fault(
                            "inventory_destination_rejected",
                            "Destination does not accept the requested exact stack/count");
                if (count < sourceBefore.getCount() && !source.mayPlace(sourceBefore))
                    throw new Fault(
                            "inventory_partial_result_unsupported",
                            "This output slot cannot accept the untransferred remainder; withdraw its entire stack");
                ownedMenu = menu;
                recoverySlot = sourceIndex;
                stage = 1;
            }
            if (!session.ready()) return null;
            AbstractContainerMenu menu = session.menu;
            if (stage == 1) {
                if (!ItemStack.matches(sourceBefore, menu.getSlot(sourceIndex).getItem())
                        || !ItemStack.matches(
                                destinationBefore, menu.getSlot(destinationIndex).getItem()))
                    throw new Fault(
                            "inventory_source_changed",
                            "Authoritative slots changed after the observation; reinspect before transferring");
                requireEmptyCursor(menu);
                ownedCursor = sourceBefore.copyWithCount(1);
                click(session, sourceIndex, 0, ClickType.PICKUP);
                stage = 2;
                return null;
            }
            if (stage == 2) {
                requireStack(
                        menu.getCarried(),
                        sourceBefore,
                        sourceBefore.getCount(),
                        "Server did not confirm the picked-up source stack");
                if (!menu.getSlot(sourceIndex).getItem().isEmpty())
                    throw new Fault(
                            "inventory_pickup_mismatch",
                            "Source was not emptied by the native pickup");
                if (count == sourceBefore.getCount()) {
                    click(session, destinationIndex, 0, ClickType.PICKUP);
                    deposited = count;
                    stage = 4;
                } else {
                    click(session, destinationIndex, 1, ClickType.PICKUP);
                    deposited = 1;
                    stage = 3;
                }
                return null;
            }
            if (stage == 3) {
                requireStack(
                        menu.getCarried(),
                        sourceBefore,
                        sourceBefore.getCount() - deposited,
                        "Server cursor differs during exact-count transfer");
                requireStack(
                        menu.getSlot(destinationIndex).getItem(),
                        sourceBefore,
                        destinationBefore.getCount() + deposited,
                        "Destination differs during exact-count transfer");
                if (deposited < count) {
                    click(session, destinationIndex, 1, ClickType.PICKUP);
                    deposited++;
                } else {
                    click(session, sourceIndex, 0, ClickType.PICKUP);
                    stage = 4;
                }
                return null;
            }
            requireEmptyCursor(menu);
            requireStack(
                    menu.getSlot(sourceIndex).getItem(),
                    sourceBefore,
                    sourceBefore.getCount() - count,
                    "Final source count/components differ");
            requireStack(
                    menu.getSlot(destinationIndex).getItem(),
                    sourceBefore,
                    destinationBefore.getCount() + count,
                    "Final destination count/components differ");
            JsonObject data = snapshot(mc, menu, sourceBefore);
            data.addProperty("transferred", count);
            data.addProperty("itemName", WorldData.name(sourceBefore));
            data.addProperty("sourceSlot", sourceIndex);
            data.addProperty("destinationSlot", destinationIndex);
            data.addProperty("confirmedBy", "server_full_content");
            return GameBridge.ok("Exact native slot transfer confirmed by server contents", data);
        }
    }

    private static final class Craft extends Action {
        final Session session;
        final JsonObject args;
        RecipeBookMenu<CraftingInput, CraftingRecipe> crafting;
        RecipeHolder<CraftingRecipe> recipe;
        ItemStack output, destinationBefore, clearingStack;
        int stage, wanted, baseline, previous, destination, crafts, clearingBefore, clearing = -1;

        Craft(Minecraft mc, JsonObject args, CancellationToken token) {
            super(mc, token);
            session = new Session(mc, args);
            this.args = args;
        }

        @SuppressWarnings("unchecked")
        @Override
        JsonObject step() {
            if (!session.ready()) return null;
            AbstractContainerMenu menu = session.menu;
            if (stage == 0) {
                requireEmptyCursor(menu);
                if (!(menu instanceof InventoryMenu) && !(menu instanceof CraftingMenu))
                    throw new Fault(
                            "crafting_menu_unsupported",
                            "Ordinary crafting requires the native player inventory or crafting table");
                crafting = (RecipeBookMenu<CraftingInput, CraftingRecipe>) menu;
                if (firstGridItem(crafting) >= 0)
                    throw new Fault(
                            "crafting_grid_not_empty",
                            "Existing crafting-grid contents belong to the player; clear them explicitly first");
                wanted = WorldData.integer(args, "count", 1, 1, 64);
                recipe = chooseRecipe(mc, crafting, args);
                output = recipe.value().getResultItem(level.registryAccess()).copy();
                baseline = inventoryCount(mc, output);
                previous = baseline;
                ownedMenu = menu;
                stage = 1;
            }
            if (stage == 1) {
                if (++crafts > 64)
                    throw new Fault(
                            "crafting_no_progress",
                            "Crafting exhausted its bounded native batch count");
                mc.gameMode.handlePlaceRecipe(menu.containerId, recipe, false);
                session.synchronize();
                stage = 2;
                return null;
            }
            if (stage == 2) {
                requireEmptyCursor(menu);
                if (!crafting.recipeMatches(recipe))
                    throw new Fault(
                            "crafting_recipe_not_placed",
                            "Server did not place the selected unlocked recipe; ingredients or recipe availability may have changed");
                ItemStack result = menu.getSlot(crafting.getResultSlotIndex()).getItem();
                if (!same(result, output) || result.getCount() != output.getCount())
                    throw new Fault(
                            "crafting_output_mismatch",
                            "Actual server result does not match the selected ordinary recipe output");
                destination = inventoryDestination(mc, menu, output);
                if (destination < 0)
                    throw new Fault(
                            "crafting_inventory_full",
                            "No ordinary player slot can safely receive this recipe's complete output batch");
                destinationBefore = menu.getSlot(destination).getItem().copy();
                recoverySlot = destination;
                ownedCursor = output.copyWithCount(1);
                click(session, crafting.getResultSlotIndex(), 0, ClickType.PICKUP);
                stage = 3;
                return null;
            }
            if (stage == 3) {
                requireStack(
                        menu.getCarried(),
                        output,
                        output.getCount(),
                        "Crafted output was not confirmed on the cursor");
                if (!ItemStack.matches(destinationBefore, menu.getSlot(destination).getItem())
                        || !accepts(menu.getSlot(destination), output, output.getCount()))
                    throw new Fault(
                            "crafting_destination_changed",
                            "Selected output destination changed; cursor retained for safe recovery");
                click(session, destination, 0, ClickType.PICKUP);
                stage = 4;
                return null;
            }
            if (stage == 4) {
                requireEmptyCursor(menu);
                requireStack(
                        menu.getSlot(destination).getItem(),
                        output,
                        destinationBefore.getCount() + output.getCount(),
                        "Crafted output destination count/components differ");
                int current = inventoryCount(mc, output);
                if (current <= previous)
                    throw new Fault(
                            "crafting_output_unconfirmed",
                            "Crafting did not increase actual matching output holdings");
                previous = current;
                stage = current - baseline >= wanted ? 5 : 1;
                return null;
            }
            if (clearing >= 0) {
                if (!menu.getSlot(clearing).getItem().isEmpty())
                    throw new Fault(
                            "crafting_remainder_unconfirmed",
                            "Server did not return the remaining crafting material to inventory");
                if (inventoryCount(mc, clearingStack) != clearingBefore + clearingStack.getCount())
                    throw new Fault(
                            "crafting_remainder_unconfirmed",
                            "Crafting remainder was not fully confirmed in matching player holdings");
                clearing = -1;
            }
            int remaining = firstGridItem(crafting);
            if (remaining >= 0) {
                ItemStack stack = menu.getSlot(remaining).getItem();
                if (inventoryCapacity(mc, menu, stack) < stack.getCount())
                    throw new Fault(
                            "crafting_remainder_no_space",
                            "Keep the menu open: inventory cannot safely hold its crafting remainder");
                clearing = remaining;
                clearingStack = stack.copy();
                clearingBefore = inventoryCount(mc, stack);
                click(session, remaining, 0, ClickType.QUICK_MOVE);
                return null;
            }
            JsonObject data = snapshot(mc, menu, output);
            data.addProperty("recipeId", recipe.id().toString());
            data.addProperty("itemName", WorldData.name(output));
            data.addProperty("requested", wanted);
            data.addProperty("produced", inventoryCount(mc, output) - baseline);
            data.addProperty("nativeBatches", crafts);
            data.addProperty("confirmedBy", "server_full_content");
            return GameBridge.ok(
                    "Ordinary recipe output and cleared crafting grid confirmed", data);
        }
    }

    /** One input per batch prevents overfeeding and permits exact authoritative accounting. */
    private static final class Smelt extends Action {
        final Session session;
        final JsonObject args;
        AbstractFurnaceMenu furnace;
        RecipeType<? extends AbstractCookingRecipe> recipeType;
        RecipeHolder<? extends AbstractCookingRecipe> recipe;
        ItemStack output, moving, sourceBefore, destinationBefore;
        int stage, wanted, destination, source, target, moveStage;
        SmeltingProgress progress;
        long batchSince, pollSince;

        Smelt(Minecraft mc, JsonObject args, CancellationToken token) {
            super(mc, token);
            session = new Session(mc, args);
            this.args = args;
        }

        @Override
        JsonObject step() {
            if (!session.ready()) return null;
            AbstractContainerMenu menu = session.menu;
            if (stage == 0) {
                requireEmptyCursor(menu);
                recipeType =
                        menu instanceof FurnaceMenu
                                ? RecipeType.SMELTING
                                : menu instanceof BlastFurnaceMenu
                                        ? RecipeType.BLASTING
                                        : menu instanceof SmokerMenu ? RecipeType.SMOKING : null;
                if (recipeType == null)
                    throw new Fault(
                            "smelting_menu_unsupported",
                            "Open a native furnace, blast furnace or smoker");
                furnace = (AbstractFurnaceMenu) menu;
                if (!menu.getSlot(0).getItem().isEmpty() || !menu.getSlot(2).getItem().isEmpty())
                    throw new Fault(
                            "smelting_slots_not_empty",
                            "Clear existing input/output explicitly before smelting");
                wanted = WorldData.integer(args, "count", 1, 1, 64);
                ItemStack requested = item(args, "itemName");
                if (args.has("fuelItemName")) item(args, "fuelItemName");
                for (var candidate : level.getRecipeManager().getAllRecipesFor(recipeType)) {
                    if (candidate.value().isSpecial()
                            || candidate.value().getResultItem(level.registryAccess()).getItem()
                                    != requested.getItem()) continue;
                    if (ingredientSource(candidate) >= 0
                            && (recipe == null
                                    || candidate.id().toString().compareTo(recipe.id().toString())
                                            < 0)) recipe = candidate;
                }
                if (recipe == null)
                    throw new Fault(
                            "smelting_recipe_unavailable",
                            "No synchronized matching cooking recipe with available input for this furnace type");
                output = recipe.value().getResultItem(level.registryAccess()).copy();
                progress = new SmeltingProgress(inventoryCount(mc, output), wanted);
                ownedMenu = menu;
                stage = 1;
            }
            if (stage == 1) {
                requireEmptyCursor(menu);
                if (!menu.getSlot(0).getItem().isEmpty() || !menu.getSlot(2).getItem().isEmpty())
                    throw new Fault(
                            "smelting_slots_changed",
                            "Furnace input/output changed between batches");
                if (inventoryDestination(mc, menu, output) < 0)
                    throw new Fault(
                            "smelting_inventory_full",
                            "No player slot can receive a full output batch");
                source = ingredientSource(recipe);
                if (source < 0)
                    throw new Fault(
                            "smelting_ingredients_missing", "No matching cooking input remains");
                // Validate fuel availability before moving any input.
                if (!furnace.isLit() && menu.getSlot(1).getItem().isEmpty() && fuelSource() < 0)
                    throw new Fault(
                            "smelting_fuel_missing", "No compatible selected fuel is available");
                beginMove(source, 0);
                batchSince = System.nanoTime();
                stage = 2;
                return null;
            }
            if (stage == 2 || stage == 3) {
                if (!finishMove()) return null;
                stage = 4;
            }
            if (stage == 4) {
                requireEmptyCursor(menu);
                ItemStack result = menu.getSlot(2).getItem();
                if (!result.isEmpty()) {
                    if (!same(result, output)
                            || result.getCount() != output.getCount()
                            || !menu.getSlot(0).getItem().isEmpty())
                        throw new Fault(
                                "smelting_output_mismatch",
                                "Server furnace output/input differs from the selected batch");
                    destination = inventoryDestination(mc, menu, output);
                    if (destination < 0)
                        throw new Fault("smelting_inventory_full", "Output destination is full");
                    destinationBefore = menu.getSlot(destination).getItem().copy();
                    recoverySlot = destination;
                    ownedCursor = output.copyWithCount(1);
                    click(session, 2, 0, ClickType.PICKUP);
                    stage = 5;
                    return null;
                }
                if (System.nanoTime() - batchSince > 45_000_000_000L)
                    throw new Fault(
                            "smelting_no_progress",
                            "No confirmed output within 45 seconds; furnace contents retained");
                if (!furnace.isLit() && menu.getSlot(1).getItem().isEmpty()) {
                    int fuel = fuelSource();
                    if (fuel < 0)
                        throw new Fault(
                                "smelting_fuel_missing", "Cooking needs more compatible fuel");
                    beginMove(fuel, 1);
                    stage = 3;
                    return null;
                }
                if (System.nanoTime() - pollSince > 500_000_000L) {
                    pollSince = System.nanoTime();
                    session.synchronize();
                }
                return null;
            }
            if (stage == 5) {
                if (!menu.getSlot(2).getItem().isEmpty())
                    throw new Fault(
                            "smelting_output_mismatch",
                            "Furnace output did not empty after pickup");
                requireStack(
                        menu.getCarried(),
                        output,
                        output.getCount(),
                        "Smelted output not confirmed on cursor");
                if (!ItemStack.matches(destinationBefore, menu.getSlot(destination).getItem())
                        || !accepts(menu.getSlot(destination), output, output.getCount()))
                    throw new Fault(
                            "smelting_destination_changed",
                            "Output destination changed; preserve cursor for recovery");
                click(session, destination, 0, ClickType.PICKUP);
                stage = 6;
                return null;
            }
            requireEmptyCursor(menu);
            requireStack(
                    menu.getSlot(destination).getItem(),
                    output,
                    destinationBefore.getCount() + output.getCount(),
                    "Output destination count/components differ");
            int current = inventoryCount(mc, output);
            boolean complete;
            try {
                complete = progress.confirm(current, output.getCount());
            } catch (IllegalStateException error) {
                throw new Fault("smelting_output_unconfirmed", error.getMessage());
            }
            if (!complete) {
                stage = 1;
                return null;
            }
            JsonObject data = snapshot(mc, menu, output);
            data.addProperty("recipeId", recipe.id().toString());
            data.addProperty("itemName", WorldData.name(output));
            data.addProperty("requested", wanted);
            data.addProperty("produced", progress.produced());
            data.addProperty("confirmedBy", "server_full_content");
            return GameBridge.ok("Native cooking output confirmed in player inventory", data);
        }

        int ingredientSource(RecipeHolder<? extends AbstractCookingRecipe> candidate) {
            for (int i = 3; i < session.menu.slots.size(); i++) {
                Slot slot = session.menu.getSlot(i);
                if (slotBacking(slot).ordinaryPlayer(mc)
                        && slot.mayPickup(player)
                        && !slot.getItem().isEmpty()
                        && candidate.value().matches(new SingleRecipeInput(slot.getItem()), level))
                    return i;
            }
            return -1;
        }

        int fuelSource() {
            ItemStack selected = args.has("fuelItemName") ? item(args, "fuelItemName") : null;
            for (int i = 3; i < session.menu.slots.size(); i++) {
                Slot slot = session.menu.getSlot(i);
                ItemStack stack = slot.getItem();
                if (slotBacking(slot).ordinaryPlayer(mc)
                        && slot.mayPickup(player)
                        && !stack.isEmpty()
                        && (selected == null || stack.getItem() == selected.getItem())
                        && stack.getBurnTime(recipeType) > 0
                        && accepts(session.menu.getSlot(1), stack, 1)) return i;
            }
            return -1;
        }

        void beginMove(int from, int to) {
            requireEmptyCursor(session.menu);
            source = from;
            target = to;
            sourceBefore = session.menu.getSlot(source).getItem().copy();
            moving = sourceBefore.copyWithCount(1);
            if (!accepts(session.menu.getSlot(target), moving, 1))
                throw new Fault(
                        "smelting_slot_rejected", "Machine slot rejects selected ingredient/fuel");
            recoverySlot = source;
            ownedCursor = moving;
            click(session, source, 0, ClickType.PICKUP);
            moveStage = 0;
        }

        boolean finishMove() {
            AbstractContainerMenu menu = session.menu;
            if (moveStage == 0) {
                requireStack(
                        menu.getCarried(),
                        moving,
                        sourceBefore.getCount(),
                        "Input pickup unconfirmed");
                if (!menu.getSlot(source).getItem().isEmpty())
                    throw new Fault("smelting_source_changed", "Input source was not emptied");
                if (!accepts(menu.getSlot(target), moving, 1))
                    throw new Fault("smelting_slot_rejected", "Machine destination changed");
                click(session, target, 1, ClickType.PICKUP);
                moveStage = 1;
                return false;
            }
            if (moveStage == 1) {
                requireStack(
                        menu.getCarried(),
                        moving,
                        sourceBefore.getCount() - 1,
                        "Single input placement unconfirmed");
                if (!menu.getCarried().isEmpty()) {
                    if (!menu.getSlot(source).getItem().isEmpty())
                        throw new Fault("smelting_source_changed", "Remainder source changed");
                    click(session, source, 0, ClickType.PICKUP);
                    moveStage = 2;
                    return false;
                }
            }
            requireEmptyCursor(menu);
            requireStack(
                    menu.getSlot(source).getItem(),
                    moving,
                    sourceBefore.getCount() - 1,
                    "Input remainder not confirmed in player inventory");
            return true;
        }
    }

    private static final class Close extends Action {
        final Session session;
        boolean sent;

        Close(Minecraft mc, JsonObject args, CancellationToken token) {
            super(mc, token);
            session = new Session(mc, new JsonObject());
        }

        @Override
        JsonObject step() {
            if (!sent) {
                AbstractContainerMenu menu = player.containerMenu;
                requireEmptyCursor(menu);
                if (menu instanceof InventoryMenu inventory && firstGridItem(inventory) >= 0
                        || menu instanceof CraftingMenu table && firstGridItem(table) >= 0)
                    throw new Fault(
                            "crafting_grid_not_empty",
                            "Return crafting-grid contents before closing to avoid implicit dropped materials");
                if (mc.screen != null
                        && !BotControl.isControlScreen(mc)
                        && !(mc.screen instanceof AbstractContainerScreen<?>))
                    throw new Fault(
                            "inventory_window_busy",
                            "Current screen is not a native container screen");
                if (menu != player.inventoryMenu || mc.screen instanceof AbstractContainerScreen<?>)
                    player.closeContainer();
                sent = true;
            }
            return session.ready()
                    ? GameBridge.ok(
                            "Native menu closed and server inventory synchronized",
                            snapshot(mc, session.menu, null))
                    : null;
        }
    }

    private static RecipeHolder<CraftingRecipe> chooseRecipe(
            Minecraft mc, RecipeBookMenu<CraftingInput, CraftingRecipe> menu, JsonObject args) {
        ItemStack requested = item(args, "itemName");
        String explicit = WorldData.string(args, "recipeId", "");
        StackedContents contents = new StackedContents();
        mc.player.getInventory().fillStackedContents(contents);
        List<RecipeHolder<CraftingRecipe>> candidates =
                new ArrayList<>(mc.level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING));
        candidates.sort(Comparator.comparing(holder -> holder.id().toString()));
        for (RecipeHolder<CraftingRecipe> candidate : candidates) {
            CraftingRecipe recipe = candidate.value();
            ItemStack result = recipe.getResultItem(mc.level.registryAccess());
            if ((!explicit.isEmpty() && !candidate.id().toString().equals(explicit))
                    || result.isEmpty()
                    || result.getItem() != requested.getItem()) continue;
            if (recipe.isSpecial()
                    || !recipe.canCraftInDimensions(menu.getGridWidth(), menu.getGridHeight())
                    || !mc.player.getRecipeBook().contains(candidate)) continue;
            if (contents.canCraft(recipe, null)) return candidate;
        }
        throw new Fault(
                "crafting_recipe_unavailable",
                "No synchronized, unlocked ordinary recipe for this output fits the current grid and available simple ingredients; inspect actual recipes and ingredients");
    }

    private static ItemStack item(JsonObject args, String key) {
        if (!args.has(key))
            throw new Fault("inventory_item_required", "Missing exact namespaced " + key);
        ResourceLocation id = ResourceLocation.tryParse(args.get(key).getAsString());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id))
            throw new Fault("inventory_item_unknown", "Unknown current-client item identifier");
        ItemStack stack = BuiltInRegistries.ITEM.get(id).getDefaultInstance();
        if (stack.isEmpty())
            throw new Fault("inventory_item_unknown", "Air is not an inventory item");
        return stack;
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameComponents(a, b);
    }

    private static boolean accepts(Slot slot, ItemStack stack, int count) {
        ItemStack existing = slot.getItem();
        return slot.isActive()
                && slot.mayPlace(stack)
                && (existing.isEmpty() || same(existing, stack))
                && slot.getMaxStackSize(stack) - existing.getCount() >= count;
    }

    private static void requireEmptyCursor(AbstractContainerMenu menu) {
        if (!menu.getCarried().isEmpty())
            throw new Fault(
                    "inventory_cursor_not_empty",
                    "Resolve the actual carried stack before another inventory operation");
    }

    private static void requireStack(
            ItemStack actual, ItemStack identity, int count, String message) {
        if (count == 0 ? !actual.isEmpty() : !same(actual, identity) || actual.getCount() != count)
            throw new Fault("inventory_transfer_mismatch", message);
    }

    private record SlotBacking(Object identity, int index, Container container) {
        boolean isPlayer(Minecraft mc) {
            return container == mc.player.getInventory();
        }

        boolean ordinaryPlayer(Minecraft mc) {
            return isPlayer(mc) && index >= 0 && index < mc.player.getInventory().items.size();
        }

        boolean aliases(SlotBacking other) {
            return identity == other.identity && index == other.index;
        }
    }

    private static SlotBacking slotBacking(Slot slot) {
        int index = slot.getContainerSlot();
        Container container = slot.container;
        if (slot instanceof SlotItemHandler wrapped) {
            // SlotItemHandler.container is a shared empty placeholder, never its storage identity.
            var handler = wrapped.getItemHandler();
            if (index < 0 || index >= handler.getSlots())
                return new SlotBacking(handler, index, null);
            container =
                    handler instanceof InvWrapper inventory
                            ? inventory.getInv()
                            : handler instanceof PlayerMainInvWrapper main
                                    ? main.getInventoryPlayer()
                                    : null;
            if (container == null || index < 0 || index >= container.getContainerSize())
                return new SlotBacking(handler, index, null);
        }
        if (index < 0 || index >= container.getContainerSize())
            return new SlotBacking(slot, index, null);
        return new SlotBacking(container, index, container);
    }

    private static int inventoryDestination(
            Minecraft mc, AbstractContainerMenu menu, ItemStack stack) {
        int empty = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (!slotBacking(slot).ordinaryPlayer(mc) || !accepts(slot, stack, stack.getCount()))
                continue;
            if (!slot.getItem().isEmpty()) return i;
            if (empty < 0) empty = i;
        }
        return empty;
    }

    private static int inventoryCapacity(
            Minecraft mc, AbstractContainerMenu menu, ItemStack stack) {
        int capacity = 0;
        BitSet counted = new BitSet(mc.player.getInventory().items.size());
        for (Slot slot : menu.slots) {
            SlotBacking backing = slotBacking(slot);
            if (!backing.ordinaryPlayer(mc)
                    || counted.get(backing.index())
                    || !slot.isActive()
                    || !slot.mayPlace(stack)
                    || !slot.getItem().isEmpty() && !same(slot.getItem(), stack)) continue;
            counted.set(backing.index());
            capacity += Math.max(0, slot.getMaxStackSize(stack) - slot.getItem().getCount());
        }
        return capacity;
    }

    private static int inventoryCount(Minecraft mc, ItemStack stack) {
        int count = 0;
        for (int i = 0; i < 36; i++)
            if (same(mc.player.getInventory().getItem(i), stack))
                count += mc.player.getInventory().getItem(i).getCount();
        return count;
    }

    private static int firstGridItem(RecipeBookMenu<CraftingInput, CraftingRecipe> menu) {
        for (int i = 0; i < menu.getSize(); i++)
            if (i != menu.getResultSlotIndex() && !menu.getSlot(i).getItem().isEmpty()) return i;
        return -1;
    }
}
