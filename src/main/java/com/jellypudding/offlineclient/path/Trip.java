package com.jellypudding.offlineclient.path;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

// One walk from where the player stands to a goal. Searches again whenever the
// walker loses the path and gives up after too many tries.
public final class Trip {

    public enum State { IDLE, WALKING, ARRIVED, FAILED }

    private static final int MAX_SEARCHES = 32;

    // How close counts as there when the path stops short of the goal.
    private static final double CLOSE_ENOUGH = 1.5;

    private final PathFinder finder = new PathFinder();
    private final PathWalker walker = new PathWalker();

    private PathGoal goal;
    private BlockPos target;
    private int searches;
    private State state = State.IDLE;

    public PathFinder finder() {
        return finder;
    }

    public PathWalker walker() {
        return walker;
    }

    public State state() {
        return state;
    }

    public boolean active() {
        return state == State.WALKING;
    }

    // Radius nought means the exact block.
    public boolean start(BlockPos target, double radius) {
        stop();
        this.target = target.immutable();
        goal = radius <= 0 ? new PathGoal.Spot(target) : new PathGoal.Around(target, radius);
        searches = 0;
        state = State.WALKING;
        return search();
    }

    public void stop() {
        finder.cancel();
        walker.stop();
        goal = null;
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
            if (result.nodes().size() < 2) {
                return end(nearTarget(player) ? State.ARRIVED : State.FAILED);
            }
            walker.follow(result.nodes());
        }
        if (finder.busy()) {
            return state;
        }
        if (walker.arrived() || walker.lost()) {
            if (nearTarget(player) || goal.reached(PathFinder.standingAt(player))) {
                return end(State.ARRIVED);
            }
            if (searches >= MAX_SEARCHES || !search()) {
                return end(State.FAILED);
            }
            return state;
        }
        walker.tick(finder.liveRules());
        return state;
    }

    private boolean search() {
        searches++;
        return finder.search(PathFinder.standingAt(OfflineClient.MC.player), goal);
    }

    private boolean nearTarget(LocalPlayer player) {
        return player.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5)
            <= CLOSE_ENOUGH * CLOSE_ENOUGH;
    }

    private State end(State outcome) {
        finder.cancel();
        walker.stop();
        state = outcome;
        return outcome;
    }
}
