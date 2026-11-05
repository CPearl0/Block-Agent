package io.github.cpearl0.blockagent.game;

import com.google.gson.JsonObject;
import io.github.cpearl0.blockagent.core.BotConfig;
import io.github.cpearl0.blockagent.core.HostSurvivalPolicy;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Client-tick survival. Reflex choice is {@link HostSurvivalPolicy}; this class only reads the
 * loaded world and drives ordinary controls. It does not equip armor, raise a shield, shoot, place
 * water, or build cover. Retreat is a straight walk away from the nearest hostile, not a path
 * through walls. Same-dimension respawn is a client command packet; hardcore is left on the death
 * screen.
 */
final class HostSurvival {
    private static final Set<String> SKIP_FOOD =
            Set.of(
                    "minecraft:rotten_flesh",
                    "minecraft:spider_eye",
                    "minecraft:poisonous_potato",
                    "minecraft:pufferfish",
                    "minecraft:chicken",
                    "minecraft:suspicious_stew");

    record Decision(boolean preempt, boolean interrupt, JsonObject evidence) {
        static Decision clear() {
            return new Decision(false, false, null);
        }
    }

    private int respawnDelay;
    private int threatTicks;
    private boolean holdingRetreat;
    private boolean driving;
    private int eatPhase;
    private int eatTicks;
    private int eatCooldown;
    private int eatStartCount;
    private int savedHotbar = -1;
    private boolean pickedFromInventory;
    private String eatingName = "";
    private double nearestHostile = Double.POSITIVE_INFINITY;

    void tickDeath(Minecraft mc, BotConfig.Survival survival, boolean taskRunning) {
        if (mc.player == null || mc.player.isAlive()) {
            respawnDelay = 0;
            return;
        }
        if (!taskRunning || !survival.autoResumeAfterDeath()) return;
        if (mc.level == null || mc.level.getLevelData().isHardcore()) return;
        if (mc.player.connection == null) return;
        if (respawnDelay++ % 20 != 0) return;
        mc.player.respawn();
    }

    Decision tickLiving(
            Minecraft mc, BotConfig.Survival survival, boolean taskRunning, String tool) {
        if (!taskRunning || mc.player == null || mc.level == null || !mc.player.isAlive()) {
            holdingRetreat = false;
            threatTicks = 0;
            cancelEat(mc);
            stopMotion(mc);
            return Decision.clear();
        }
        if (!survival.hostThreatPolicy()) {
            holdingRetreat = false;
            threatTicks = 0;
            cancelEat(mc);
            stopMotion(mc);
            return Decision.clear();
        }
        if (HostSurvivalPolicy.needsAir(mc.player.isInWater(), mc.player.getAirSupply())
                && BotControl.isActive()) {
            cancelEat(mc);
            stopMotion(mc);
            if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
            if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
            BotControl.setKeyDown(mc.options.keyJump, true);
            driving = true;
            boolean interrupt = ++threatTicks >= HostSurvivalPolicy.THREAT_INTERRUPT_TICKS;
            if (interrupt) threatTicks = 0;
            JsonObject evidence = new JsonObject();
            evidence.addProperty("oxygen", mc.player.getAirSupply());
            evidence.addProperty(
                    "scope", "Low air: holding native jump to surface; route unverified");
            return new Decision(HostSurvivalPolicy.yieldsToThreat(tool), interrupt, evidence);
        }
        Sense sense = sense(mc);
        nearestHostile = sense.nearest;
        boolean canSteer = BotControl.isActive();
        HostSurvivalPolicy.Reflex reflex =
                HostSurvivalPolicy.choose(
                        new HostSurvivalPolicy.Signals(
                                true,
                                true,
                                mc.player.getHealth(),
                                mc.player.getFoodData().getFoodLevel(),
                                sense.threatened,
                                sense.nearest,
                                holdingRetreat,
                                sense.edible,
                                sense.canEatNow));
        if (reflex == HostSurvivalPolicy.Reflex.RETREAT) {
            cancelEat(mc);
            if (canSteer) retreat(mc, sense);
            else if (mc.gameMode != null && tool != null && !tool.isEmpty())
                mc.gameMode.stopDestroyBlock();
            holdingRetreat = true;
            threatTicks++;
            boolean yield = yields(tool, reflex);
            boolean interrupt = yield && threatTicks >= HostSurvivalPolicy.THREAT_INTERRUPT_TICKS;
            if (interrupt) {
                threatTicks = 0;
                holdingRetreat = false;
                stopMotion(mc);
            }
            return new Decision(yield, interrupt, threatEvidence(tool));
        }
        threatTicks = 0;
        holdingRetreat = false;
        if ("eat".equals(tool)) {
            if (eatPhase != 0) cancelEat(mc);
            stopMotion(mc);
            return Decision.clear();
        }
        if (reflex == HostSurvivalPolicy.Reflex.EAT || eatPhase != 0) {
            if (reflex == HostSurvivalPolicy.Reflex.EAT && eatPhase == 0 && eatCooldown > 0) {
                eatCooldown--;
                stopMotion(mc);
                return Decision.clear();
            }
            boolean busy = eat(mc, canSteer);
            if (!busy && eatPhase == 0) {
                eatCooldown = 80;
                stopMotion(mc);
                return Decision.clear();
            }
            return new Decision(yields(tool, HostSurvivalPolicy.Reflex.EAT), false, null);
        }
        if (eatCooldown > 0) eatCooldown--;
        stopMotion(mc);
        return Decision.clear();
    }

    private static boolean yields(String tool, HostSurvivalPolicy.Reflex reflex) {
        if (tool == null || tool.isEmpty() || !HostSurvivalPolicy.yieldsToThreat(tool))
            return false;
        return !(reflex == HostSurvivalPolicy.Reflex.EAT && tool.equals("eat"));
    }

    private JsonObject threatEvidence(String tool) {
        JsonObject data = new JsonObject();
        data.addProperty("pausedTool", tool == null ? "" : tool);
        if (Double.isFinite(nearestHostile)) data.addProperty("nearestHostile", nearestHostile);
        data.addProperty(
                "scope",
                "Host threat policy paused the action on the client tick. This is not a human stop.");
        return data;
    }

    private void retreat(Minecraft mc, Sense sense) {
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        Vec3 away;
        if (sense.focus != null) {
            away = mc.player.position().subtract(sense.focus);
            if (away.horizontalDistanceSqr() < 1.0e-4)
                away =
                        new Vec3(
                                -Math.sin(Math.toRadians(mc.player.getYRot())),
                                0,
                                Math.cos(Math.toRadians(mc.player.getYRot())));
        } else if (sense.water != null)
            away = Vec3.atCenterOf(sense.water).subtract(mc.player.position());
        else away = new Vec3(-mc.player.getLookAngle().x, 0, -mc.player.getLookAngle().z);
        if (away.lengthSqr() < 1.0e-4) away = new Vec3(0, 0, 1);
        Navigation.look(mc, mc.player.getEyePosition().add(away.normalize()));
        mc.player.setXRot(0);
        BotControl.setKeyDown(mc.options.keyDown, false);
        BotControl.setKeyDown(mc.options.keyLeft, false);
        BotControl.setKeyDown(mc.options.keyRight, false);
        BotControl.setKeyDown(mc.options.keyUp, true);
        BotControl.setKeyDown(mc.options.keySprint, true);
        BotControl.setKeyDown(
                mc.options.keyJump, mc.player.horizontalCollision && mc.player.onGround());
        BotControl.setKeyDown(mc.options.keyUse, false);
        if (mc.player.isUsingItem() && mc.gameMode != null) mc.gameMode.releaseUsingItem(mc.player);
        driving = true;
    }

    private void stopMotion(Minecraft mc) {
        if (!driving) return;
        driving = false;
        if (mc.player == null) return;
        Navigation.stop(mc);
        BotControl.setKeyDown(mc.options.keyUse, false);
        if (mc.player.isUsingItem() && mc.gameMode != null) mc.gameMode.releaseUsingItem(mc.player);
    }

    private boolean eat(Minecraft mc, boolean canSteer) {
        if (!canSteer || mc.gameMode == null) return false;
        if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        if (eatPhase == 0) {
            Navigation.stop(mc);
            driving = false;
            int slot = bestFoodSlot(mc);
            if (slot < 0) return false;
            savedHotbar = mc.player.getInventory().selected;
            pickedFromInventory = false;
            eatingName = WorldData.name(mc.player.getInventory().getItem(slot));
            if (slot > 8) {
                mc.gameMode.handlePickItem(slot);
                pickedFromInventory = true;
                eatPhase = 1;
                eatTicks = 0;
                driving = true;
                return true;
            }
            mc.player.getInventory().selected = slot;
            return beginUse(mc);
        }
        if (eatPhase == 1) {
            ItemStack held = mc.player.getMainHandItem();
            if (!held.isEmpty() && WorldData.name(held).equals(eatingName)) return beginUse(mc);
            if (++eatTicks > 40) {
                cancelEat(mc);
                return false;
            }
            return true;
        }
        BotControl.setKeyDown(mc.options.keyUse, true);
        driving = true;
        if (++eatTicks > 80 || count(mc, eatingName) < eatStartCount) {
            finishEat(mc);
            return false;
        }
        return true;
    }

    private boolean beginUse(Minecraft mc) {
        ItemStack stack = mc.player.getMainHandItem();
        if (stack.get(DataComponents.FOOD) == null) {
            cancelEat(mc);
            return false;
        }
        var outcome = mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        if (!outcome.consumesAction()) {
            cancelEat(mc);
            return false;
        }
        BotControl.setKeyDown(mc.options.keyUse, true);
        eatPhase = 2;
        eatTicks = 0;
        eatStartCount = count(mc, eatingName);
        driving = true;
        return true;
    }

    private void finishEat(Minecraft mc) {
        int restore = pickedFromInventory ? -1 : savedHotbar;
        eatPhase = 0;
        savedHotbar = -1;
        eatingName = "";
        pickedFromInventory = false;
        if (mc.player != null) {
            BotControl.setKeyDown(mc.options.keyUse, false);
            if (mc.player.isUsingItem() && mc.gameMode != null)
                mc.gameMode.releaseUsingItem(mc.player);
            if (restore >= 0) mc.player.getInventory().selected = restore;
        }
        driving = false;
    }

    private void cancelEat(Minecraft mc) {
        if (eatPhase == 0 && savedHotbar < 0) return;
        finishEat(mc);
        eatCooldown = 80;
    }

    private static int bestFoodSlot(Minecraft mc) {
        int bestSlot = -1, bestScore = Integer.MIN_VALUE;
        boolean hurt = mc.player.getHealth() <= HostSurvivalPolicy.EAT_BELOW_HEALTH;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            var food = stack.get(DataComponents.FOOD);
            if (food == null) continue;
            String name = WorldData.name(stack);
            if (SKIP_FOOD.contains(name)) continue;
            if (mc.player.getFoodData().getFoodLevel() >= 20 && !food.canAlwaysEat()) continue;
            int score = food.nutrition() * 10 + Math.round(food.saturation());
            if (hurt && food.canAlwaysEat()) score += 100;
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private static int count(Minecraft mc, String name) {
        return WorldData.inventory(mc).getOrDefault(name, 0);
    }

    private Sense sense(Minecraft mc) {
        double nearest = Double.POSITIVE_INFINITY;
        boolean swelling = false;
        Vec3 focus = null;
        AABB box = mc.player.getBoundingBox().inflate(HostSurvivalPolicy.RELEASE_DISTANCE + 4);
        for (Entity entity : mc.level.getEntities(mc.player, box)) {
            if (!hostileTo(mc, entity)) continue;
            double distance = mc.player.distanceTo(entity);
            if (entity instanceof Creeper creeper
                    && creeper.getSwelling(1f) > 0.05f
                    && distance <= HostSurvivalPolicy.RELEASE_DISTANCE) swelling = true;
            if (distance < nearest) {
                nearest = distance;
                focus = entity.position();
            }
        }
        boolean burning = (mc.player.isOnFire() || mc.player.isInLava()) && !mc.player.isInWater();
        BlockPos water = burning ? nearestWater(mc) : null;
        boolean threatened = swelling || nearest <= HostSurvivalPolicy.PAUSE_DISTANCE || burning;
        boolean edible = bestFoodSlot(mc) >= 0;
        int food = mc.player.getFoodData().getFoodLevel();
        boolean canEatNow =
                edible
                        && (food < 20
                                || mc.player.getHealth() <= HostSurvivalPolicy.EAT_BELOW_HEALTH);
        return new Sense(threatened, nearest, edible, canEatNow, focus, water);
    }

    private static BlockPos nearestWater(Minecraft mc) {
        BlockPos center = mc.player.blockPosition();
        BlockPos best = null;
        for (BlockPos pos :
                BlockPos.betweenClosed(center.offset(-6, -2, -6), center.offset(6, 2, 6))) {
            if (!WorldData.loaded(mc, pos) || !mc.level.getFluidState(pos).is(FluidTags.WATER))
                continue;
            if (best == null || pos.distSqr(center) < best.distSqr(center)) best = pos.immutable();
        }
        return best;
    }

    private static boolean hostileTo(Minecraft mc, Entity entity) {
        if (!entity.isAlive() || entity == mc.player || entity instanceof Player) return false;
        if (entity instanceof NeutralMob neutral) return neutral.isAngry();
        if (entity instanceof Enemy) return true;
        return entity instanceof Mob mob && mob.getTarget() == mc.player;
    }

    private record Sense(
            boolean threatened,
            double nearest,
            boolean edible,
            boolean canEatNow,
            Vec3 focus,
            BlockPos water) {}
}
