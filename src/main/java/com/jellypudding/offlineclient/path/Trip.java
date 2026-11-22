package com.jellypudding.offlineclient.path;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;

import java.util.List;

// One walk from where the player stands to a goal. Searches again whenever the
// walker loses the path and gives up after too many tries.
public final class Trip {

    public enum State { IDLE, WALKING, ARRIVED, FAILED }

    private static final int MAX_SEARCHES = 32;

    private static final double CLOSE_ENOUGH = 1.5;

    // A path that stops short of the goal was planned over the land known at the time and more
    // arrives as the player walks. The way on is looked for again every two seconds from a few
    // steps ahead. The walk carries on meanwhile.
    private static final int RETHINK_TICKS = 40;
    private static final int LEAD = 8;

    private final PathFinder finder = new PathFinder();
    private final PathWalker walker = new PathWalker();

    private PathGoal goal;
    private int searches;
    private State state = State.IDLE;
    private boolean stopsShort;
    private int walked;
    private boolean rethinking;

    public PathFinder finder() {
        return finder;
    }

    public PathWalker walker() {
        return walker;
    }

    public boolean active() {
        return state == State.WALKING;
    }

    // Radius nought means the exact block.
    public boolean start(BlockPos target, double radius) {
        return start(radius <= 0 ? new PathGoal.Spot(target) : new PathGoal.Around(target, radius));
    }

    public boolean start(PathGoal goal) {
        stop();
        this.goal = goal;
        searches = 0;
        finder.forgive();
        state = State.WALKING;
        return search();
    }

    public void stop() {
        finder.cancel();
        walker.stop();
        goal = null;
        rethinking = false;
        state = State.IDLE;
    }

    // Once a tick. The state stays at ARRIVED or FAILED until the next start.
    public State tick() {
        LocalPlayer player = OfflineClient.MC.player;
        if (state != State.WALKING) {
            return state;
        }
        if (player == null) {
            return end(State.FAILED);
        }
        PathFinder.Result result = finder.poll();
        if (result != null) {
            if (rethinking) {
                rejoin(result.nodes());
            } else if (result.nodes().size() < 2) {
                return end(nearTarget(player) ? State.ARRIVED : State.FAILED);
            } else {
                walker.follow(result.nodes());
                planned(result.nodes());
            }
        }
        rethinking &= finder.busy();
        if (finder.busy() && !rethinking) {
            return state;
        }
        if (walker.arrived() || walker.lost()) {
            if (nearTarget(player) || goal.reached(PathFinder.standingAt(player))) {
                return end(State.ARRIVED);
            }
            // The player stands still whilst the next way is looked for.
            walker.releaseKeys();
            finder.cancel();
            rethinking = false;
            if (!settled(player)) {
                return state;
            }
            if (walker.walled() != null) {
                finder.shun(walker.walled());
            }
            if (searches >= MAX_SEARCHES || !search()) {
                return end(State.FAILED);
            }
            return state;
        }
        walker.tick(finder.liveRules());
        rethink();
        return state;
    }

    // In mid air the player stands on nothing a path begins at.
    private static boolean settled(LocalPlayer player) {
        return player.onGround() || player.isInWater() || player.isInLava() || player.onClimbable()
            || player.isPassenger() || player.getAbilities().flying;
    }

    private boolean search() {
        searches++;
        return finder.search(PathFinder.standingAt(OfflineClient.MC.player), goal);
    }

    private void planned(List<BlockPos> nodes) {
        stopsShort = !goal.reached(nodes.getLast());
        walked = 0;
    }

    private void rethink() {
        if (!stopsShort || rethinking || walker.arrived() || ++walked < RETHINK_TICKS) {
            return;
        }
        walked = 0;
        rethinking = finder.search(walker.ahead(LEAD), goal);
    }

    private void rejoin(List<BlockPos> onward) {
        if (onward.size() < 2 || walker.arrived()) {
            return;
        }
        BlockPos end = onward.getLast();
        boolean nearer = goal.reached(end) || distance(end) < distance(walker.end());
        if (nearer && walker.rejoin(onward)) {
            planned(onward);
        }
    }

    private double distance(BlockPos node) {
        return goal.distance(Vec3.atBottomCenterOf(node));
    }

    // Measured from the middle of the block the player stands on. A walk that ends on the block
    // beside the goal is there wherever on that block it stops.
    private boolean nearTarget(LocalPlayer player) {
        return goal.distance(Vec3.atBottomCenterOf(PathFinder.standingAt(player))) <= CLOSE_ENOUGH;
    }

    private State end(State outcome) {
        finder.cancel();
        walker.stop();
        rethinking = false;
        state = outcome;
        return outcome;
    }
}
