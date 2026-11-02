package io.github.cpearl0.blockagent.game;

import com.google.gson.JsonObject;
import io.github.cpearl0.blockagent.core.CancellationToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Bounded dry-land navigation driven entirely through normal player controls. */
final class Navigation {
    private Navigation() {}

    static void stop(Minecraft mc) {
        BotControl.setKeyDown(mc.options.keyUp, false);
        BotControl.setKeyDown(mc.options.keyDown, false);
        BotControl.setKeyDown(mc.options.keyLeft, false);
        BotControl.setKeyDown(mc.options.keyRight, false);
        BotControl.setKeyDown(mc.options.keyJump, false);
        BotControl.setKeyDown(mc.options.keySprint, false);
    }

    static void look(Minecraft mc, Vec3 point) {
        Vec3 delta = point.subtract(mc.player.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, horizontal)));
    }

    /** Placement packets do not carry player rotation; send the ordinary rotation packet first. */
    static void flushLook(Minecraft mc) {
        mc.player.connection.send(
                new ServerboundMovePlayerPacket.Rot(
                        mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround()));
    }

    static final class Move implements GameBridge.Action {
        private final Minecraft mc;
        private final CancellationToken token;
        private final Controller controller;

        Move(Minecraft mc, JsonObject args, CancellationToken token) {
            this.mc = mc;
            this.token = token;
            BlockPos target = WorldData.position(args, "position");
            double range = WorldData.number(args, "range", 0, 0, 8);
            controller = new Controller(mc, target, range);
        }

        public JsonObject tick() {
            token.throwIfCancelled();
            if (!controller.tick()) return null;
            JsonObject result = new JsonObject();
            result.add("position", WorldData.xyz(mc.player.position()));
            return GameBridge.ok("Reached requested foot-cell range", result);
        }

        @Override
        public void close() {
            stop(mc);
        }
    }

    static final class Controller {
        private final Minecraft mc;
        private final BlockPos target;
        private final Predicate<BlockPos> reached;
        private List<BlockPos> route;
        private int cursor, replans, idleTicks;
        private Vec3 progress;

        Controller(Minecraft mc, BlockPos target, double range) {
            this(mc, target, p -> p.distSqr(target) <= range * range);
        }

        Controller(Minecraft mc, BlockPos target, Predicate<BlockPos> reached) {
            this.mc = mc;
            this.target = target;
            this.reached = reached;
        }

        boolean tick() {
            if (mc.screen != null && !BotControl.isControlScreen(mc)
                    || mc.player.containerMenu != mc.player.inventoryMenu
                    || !mc.player.inventoryMenu.getCarried().isEmpty())
                throw new IllegalStateException(
                        "Close the current screen/menu and empty its cursor before moving");
            if (reached.test(mc.player.blockPosition())) {
                stop(mc);
                return true;
            }
            if (mc.player.isInWater() || mc.player.isInLava() || mc.player.getAbilities().flying)
                throw new IllegalStateException(
                        "This navigation capability requires native grounded dry-land movement");
            if (route == null || cursor >= route.size()) {
                route = plan(mc, target, reached);
                cursor = 0;
                progress = mc.player.position();
                idleTicks = 0;
            }
            BlockPos next = route.get(cursor);
            Vec3 point = Vec3.atBottomCenterOf(next);
            Vec3 delta = point.subtract(mc.player.position());
            if (Math.abs(delta.y) < .55 && delta.horizontalDistanceSqr() < .0225) {
                cursor++;
                if (cursor >= route.size()) {
                    route = null;
                    return false;
                }
                next = route.get(cursor);
                point = Vec3.atBottomCenterOf(next);
                delta = point.subtract(mc.player.position());
            }
            if (!WorldData.safe(mc, next)) {
                stop(mc);
                if (++replans > 4)
                    throw new IllegalStateException("Terrain changed repeatedly during navigation");
                route = null;
                return false;
            }
            if (progress.distanceToSqr(mc.player.position()) > .04) {
                progress = mc.player.position();
                idleTicks = 0;
            } else if (++idleTicks > 45) {
                stop(mc);
                throw new IllegalStateException(
                        "navigation_stalled: player did not progress toward the native route");
            }
            look(mc, point.add(0, mc.player.getEyeHeight(), 0));
            mc.player.setXRot(0);
            BotControl.setKeyDown(mc.options.keyUp, true);
            BotControl.setKeyDown(mc.options.keyJump, delta.y > .5 && mc.player.onGround());
            BotControl.setKeyDown(mc.options.keySprint, false);
            return false;
        }
    }

    private record Node(BlockPos pos, double cost, double score) {}

    private static List<BlockPos> plan(Minecraft mc, BlockPos target, Predicate<BlockPos> reached) {
        BlockPos start = mc.player.blockPosition();
        if (start.distSqr(target) > 64 * 64)
            throw new IllegalArgumentException(
                    "Navigation destination is more than 64 blocks away; plan loaded intermediate waypoints");
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        Map<BlockPos, Double> costs = new HashMap<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        open.add(new Node(start, 0, heuristic(start, target)));
        costs.put(start, 0d);
        while (!open.isEmpty() && closed.size() < 8192) {
            Node node = open.poll();
            BlockPos here = node.pos;
            if (!closed.add(here)) continue;
            if (reached.test(here)) {
                ArrayList<BlockPos> path = new ArrayList<>();
                for (BlockPos p = here; !p.equals(start); p = parents.get(p)) {
                    if (p == null) throw new IllegalStateException("Invalid native route");
                    path.add(p);
                }
                Collections.reverse(path);
                return path;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL)
                for (int step : new int[] {0, 1, -1}) {
                    BlockPos next = here.relative(direction).above(step);
                    if (closed.contains(next)
                            || next.distSqr(start) > 64 * 64
                            || Math.abs(next.getY() - start.getY()) > 24
                            || !WorldData.safe(mc, next)) continue;
                    // A one-block rise needs jump headroom above the source as well as the landing
                    // cell.
                    if (step == 1 && !clear(mc, here.above())) continue;
                    if (step == -1 && !clear(mc, next.above())) continue;
                    double cost = node.cost + 1 + (step == 0 ? 0 : .45);
                    if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                    costs.put(next, cost);
                    parents.put(next, here);
                    open.add(new Node(next, cost, cost + heuristic(next, target)));
                }
        }
        throw new IllegalStateException(
                "navigation_no_local_route: bounded loaded dry-land search did not find a route; this does not prove global unreachability");
    }

    private static boolean clear(Minecraft mc, BlockPos p) {
        return WorldData.loaded(mc, p)
                && WorldData.loaded(mc, p.above())
                && mc.level.noCollision(
                        mc.player,
                        new AABB(
                                p.getX() + .19,
                                p.getY() + .01,
                                p.getZ() + .19,
                                p.getX() + .81,
                                p.getY() + 1.8,
                                p.getZ() + .81));
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX())
                + Math.abs(a.getZ() - b.getZ())
                + .4 * Math.abs(a.getY() - b.getY());
    }
}
