package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.path.PathFinder;
import com.jellypudding.offlineclient.path.PathGoal;
import com.jellypudding.offlineclient.path.PathWalker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// A block in the way is walked round with the pathfinder to the first spot past it on the line of
// the bounce. No walk ever steps through a portal.
public final class BounceWay {

    public enum Portals { IGNORE, STOP, GO_ROUND }

    public enum State {
        CLEAR,
        // Let go and come to a halt on the ground.
        BRAKING,
        // The pathfinder has the keys.
        WALKING,
        // Stand where you are. stoppedFor says why.
        STOPPED
    }

    // The spot past what blocks the way. A spot in a portal reads as endless to the search and it
    // takes every other way first. The view is captured on the client thread and read by the search.
    private record PastPortals(PathGoal.Spot spot, ChunkWindow view) implements PathGoal {

        @Override
        public boolean reached(BlockPos pos) {
            return spot.reached(pos);
        }

        @Override
        public double heuristic(BlockPos pos, int stepUp) {
            return teleports(view.get(pos)) || teleports(view.get(pos.above())) ? Double.POSITIVE_INFINITY
                : spot.heuristic(pos, stepUp);
        }

        @Override
        public double distance(Vec3 from) {
            return spot.distance(from);
        }
    }

    private static final Minecraft MC = OfflineClient.MC;

    private static final int STUCK_TICKS = 20;
    // A portal block whose middle lies this near the line and from a block under the feet to two
    // over the head can touch a player who keeps to the line on any heading. Half a player and
    // half the diagonal of a block come to a little over one.
    private static final double LANE = 1.25;
    private static final int BELOW = 1;
    private static final int ABOVE = 3;
    // A bounce stops within this many ticks of its speed and a few blocks more once the glide
    // shuts. The portal is acted on from that far out.
    private static final int STOP_TICKS = 8;
    private static final double STOP_ROOM = 4;
    private static final double STILL = 0.05;
    private static final int PAST_REACH = 16;
    private static final int SEARCHES = 8;
    private static final int PORTAL_VIEW = 2;
    // The heading has to turn this far for a stop to lift.
    private static final float TURNED = 45;

    private final ChunkScanner<BlockPos> portals = new ChunkScanner<>(0);
    private final PathFinder finder = new PathFinder();
    private final PathWalker walker = new PathWalker().turn(PathWalker.Turn.NONE);

    private State state = State.CLEAR;
    private BlockPos goal;
    private int searches;
    private Vec3 headway;
    private int stuckTicks;
    private String stoppedFor;
    private float stoppedYaw;
    private Vec3 stoppedAt;
    private boolean walkRound;
    private BlockPos portal;

    // Why the bounce stands still in words that follow "stopped bouncing as". Null whilst it does not.
    public String stoppedFor() {
        return state == State.STOPPED ? stoppedFor : null;
    }

    public void onPacket(Packet<?> packet) {
        portals.markChanged(packet);
    }

    // The portals already found are kept.
    public void release() {
        finder.cancel();
        walker.stop();
        state = State.CLEAR;
        goal = null;
        headway = null;
        stuckTicks = 0;
        portal = null;
    }

    // A stop short of a portal stays.
    public void standAside() {
        if (state == State.WALKING || state == State.BRAKING) {
            release();
        }
    }

    public void reset() {
        release();
        portals.reset();
    }

    // Decides what the bounce may do this tick on its heading. The walk round itself goes on in
    // walk once the bounce has let go of the keys.
    public State check(float yaw, boolean passBlocks, Portals rule) {
        LocalPlayer player = MC.player;
        Vec3 way = Vec3.directionFromRotation(0, yaw);
        if (rule == Portals.IGNORE) {
            portals.reset();
        } else {
            portals.update(ChunkScanner.heldRadius(), (view, out) -> view.forEachMatching(
                found -> found.is(Blocks.NETHER_PORTAL), (x, y, z, found) -> out.add(new BlockPos(x, y, z))));
        }
        switch (state) {
            case CLEAR -> {
                portal = rule == Portals.IGNORE ? null : portalAhead(player, way);
                if (portal != null) {
                    walkRound = rule == Portals.GO_ROUND;
                    state = State.BRAKING;
                } else if (passBlocks && blocked(player)) {
                    walkRound = true;
                    state = State.BRAKING;
                }
            }
            case BRAKING -> {
                if (player.onGround() && player.getDeltaMovement().horizontalDistance() < STILL) {
                    settle(player, way, yaw);
                }
            }
            case STOPPED -> {
                boolean turned = Math.abs(Mth.wrapDegrees(yaw - stoppedYaw)) >= TURNED;
                boolean moved = player.position().distanceTo(stoppedAt) > 1;
                if (turned || moved) {
                    state = State.CLEAR;
                    headway = null;
                }
            }
            case WALKING -> {
            }
        }
        return state;
    }

    // Drives the walk round. Called after the bounce has let go of its keys.
    public void walk() {
        if (state != State.WALKING) {
            return;
        }
        PathFinder.Result found = finder.poll();
        if (found != null) {
            List<BlockPos> nodes = found.nodes();
            // The search took a portal only for want of any other way. Searching again finds the same.
            if (throughPortal(nodes)) {
                stop(MC.player, MC.player.getYRot(), "the only way round leads through a portal to " + BlockUtil.text(goal));
                return;
            }
            if (nodes.size() < 2) {
                retry("it found no way round to ");
                return;
            }
            walker.follow(nodes);
        }
        if (finder.busy()) {
            return;
        }
        if (PathFinder.standingAt(MC.player).equals(goal)) {
            walker.stop();
            state = State.CLEAR;
            headway = null;
            return;
        }
        if (walker.arrived() || walker.lost()) {
            retry("it lost its way round to ");
            return;
        }
        walker.tick(finder.liveRules());
    }

    private void settle(LocalPlayer player, Vec3 way, float yaw) {
        double past = portal != null ? along(player, way, Vec3.atCenterOf(portal)) + 1 : firstBlocked(player, way);
        if (!walkRound) {
            stop(player, yaw, "a nether portal stands across the way at " + BlockUtil.text(portal));
            return;
        }
        if (past < 0) {
            stop(player, yaw, "it makes no headway though the way ahead looks clear");
            return;
        }
        goal = spotPast(player, way, past);
        searches = 0;
        if (goal == null || !searchRound(player)) {
            String what = portal != null ? "the nether portal at " + BlockUtil.text(portal) : "the block in the way";
            stop(player, yaw, "there is no spot to stand on past " + what);
            return;
        }
        state = State.WALKING;
    }

    private void retry(String why) {
        walker.stop();
        if (++searches < SEARCHES && searchRound(MC.player)) {
            return;
        }
        stop(MC.player, MC.player.getYRot(), why + BlockUtil.text(goal));
    }

    private boolean searchRound(LocalPlayer player) {
        BlockPos start = PathFinder.standingAt(player);
        return finder.search(start, new PastPortals(new PathGoal.Spot(goal), ChunkWindow.capture(start, PORTAL_VIEW)));
    }

    // The reason reads on from "stopped bouncing as".
    private void stop(LocalPlayer player, float yaw, String why) {
        finder.cancel();
        walker.stop();
        state = State.STOPPED;
        stoppedFor = why;
        stoppedYaw = yaw;
        stoppedAt = player.position();
        ChatUtil.error("ElytraFly stopped bouncing as " + why + ".");
    }

    private BlockPos portalAhead(LocalPlayer player, Vec3 way) {
        double reach = STOP_ROOM + player.getDeltaMovement().horizontalDistance() * STOP_TICKS;
        int feet = player.getBlockY();
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos found : portals.results()) {
            Vec3 centre = Vec3.atCenterOf(found);
            double ahead = along(player, way, centre);
            double side = Math.abs((centre.x - player.getX()) * way.z - (centre.z - player.getZ()) * way.x);
            if (ahead > 0 && ahead <= reach && ahead < best && side <= LANE && found.getY() >= feet - BELOW
                && found.getY() <= feet + ABOVE) {
                best = ahead;
                nearest = found;
            }
        }
        return nearest;
    }

    private boolean blocked(LocalPlayer player) {
        Vec3 here = player.position();
        if (headway == null || here.distanceTo(headway) >= 1) {
            headway = here;
            stuckTicks = 0;
            return false;
        }
        return ++stuckTicks >= STUCK_TICKS;
    }

    private static double along(LocalPlayer player, Vec3 way, Vec3 point) {
        return (point.x - player.getX()) * way.x + (point.z - player.getZ()) * way.z;
    }

    // Minus one when the line is clear as far as it is read.
    private static double firstBlocked(LocalPlayer player, Vec3 way) {
        for (int step = 1; step <= PAST_REACH; step++) {
            if (!canStand(spotOnLine(player, way, step))) {
                return step;
            }
        }
        return -1;
    }

    // A step up or down still counts.
    private static BlockPos spotPast(LocalPlayer player, Vec3 way, double from) {
        for (int step = (int) Math.ceil(from); step <= from + PAST_REACH; step++) {
            BlockPos spot = spotOnLine(player, way, step);
            for (int rise : new int[] {0, 1, -1}) {
                if (canStand(spot.above(rise))) {
                    return spot.above(rise);
                }
            }
        }
        return null;
    }

    private static BlockPos spotOnLine(LocalPlayer player, Vec3 way, double distance) {
        return BlockPos.containing(player.getX() + way.x * distance, player.getY() + 0.5,
            player.getZ() + way.z * distance);
    }

    private static boolean canStand(BlockPos feet) {
        return passable(feet) && passable(feet.above()) && BlockUtil.isSolid(feet.below());
    }

    private static boolean passable(BlockPos pos) {
        BlockState there = BlockUtil.state(pos);
        return MC.level.isInWorldBounds(pos) && there.getCollisionShape(MC.level, pos).isEmpty()
            && there.getFluidState().isEmpty() && !BlockUtil.harmful(there) && !teleports(there);
    }

    private static boolean throughPortal(List<BlockPos> nodes) {
        for (BlockPos node : nodes) {
            if (teleports(BlockUtil.state(node)) || teleports(BlockUtil.state(node.above()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean teleports(BlockState state) {
        return state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY);
    }
}
