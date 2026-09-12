package com.jellypudding.offlineclient.path;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Drives the player along a finished path one tick at a time.
public final class PathWalker {

    // How the view is pointed at the next node.
    public enum Turn { CLIENT, SERVER, NONE }

    private static final Minecraft MC = OfflineClient.MC;

    // How far ahead a node may be and still count as the one the player is on.
    private static final int LOOK_AHEAD = 8;

    // Ticks off the path before a new search is asked for.
    private static final int STRAY_LIMIT = 30;

    // The most degrees a client side turn moves in one tick.
    private static final float TURN_STEP = 30;

    // How closely the keys have to line up with the way to the next node.
    private static final double KEY_EDGE = 0.35;

    private List<BlockPos> path = List.of();
    private int index;
    private int strayTicks;
    private boolean lost;
    private boolean holding;

    private Turn turn = Turn.CLIENT;
    private boolean sprint = true;
    private int safeFall = 3;

    public PathWalker turn(Turn mode) {
        this.turn = mode;
        return this;
    }

    public PathWalker sprint(boolean allowed) {
        this.sprint = allowed;
        return this;
    }

    public PathWalker safeFall(int blocks) {
        this.safeFall = blocks;
        return this;
    }

    public void follow(List<BlockPos> nodes) {
        path = nodes;
        index = nodes.isEmpty() ? 0 : 1;
        strayTicks = 0;
        lost = false;
    }

    public boolean arrived() {
        return index >= path.size();
    }

    // True once the player has strayed or the world has changed under the path.
    public boolean lost() {
        return lost;
    }

    public void stop() {
        releaseKeys();
        path = List.of();
        index = 0;
        strayTicks = 0;
        lost = false;
    }

    // Lets go of every key the walker was holding and leaves the path alone.
    public void releaseKeys() {
        if (!holding) {
            return;
        }
        InputUtil.release(MC.options.keyUp);
        InputUtil.release(MC.options.keyDown);
        InputUtil.release(MC.options.keyLeft);
        InputUtil.release(MC.options.keyRight);
        InputUtil.release(MC.options.keyJump);
        InputUtil.release(MC.options.keyShift);
        InputUtil.release(MC.options.keySprint);
        holding = false;
    }

    // Called once a tick whilst a path is being walked.
    public void tick(PathFinder.Rules rules) {
        LocalPlayer player = MC.player;
        if (player == null || arrived()) {
            releaseKeys();
            return;
        }
        BlockPos here = PathFinder.standingAt(player);
        advance(here, player);
        if (arrived()) {
            releaseKeys();
            return;
        }

        BlockPos node = path.get(index);
        if (!rules.canStep(path.get(index - 1), node)) {
            lost = true;
        }
        if (breakInto(rules, node)) {
            releaseKeys();
            return;
        }

        Vec3 target = Vec3.atBottomCenterOf(node);
        face(target);
        steer(target, player);
        holding = true;

        boolean climbing = player.onClimbable();
        if (node.getY() > here.getY() && (player.onGround() || climbing)) {
            InputUtil.hold(MC.options.keyJump);
        } else if (player.isInWater() && node.getY() >= here.getY()) {
            InputUtil.hold(MC.options.keyJump);
        } else {
            InputUtil.release(MC.options.keyJump);
        }
        // Forward is what climbs a ladder. Let go of it on the way down.
        if (climbing && node.getY() < here.getY()) {
            InputUtil.release(MC.options.keyUp);
        }

        if (!climbing && nearDrop(rules, here, node)) {
            InputUtil.hold(MC.options.keyShift);
        } else {
            InputUtil.release(MC.options.keyShift);
        }

        if (sprint && !player.isInWater() && node.getY() >= here.getY()) {
            InputUtil.hold(MC.options.keySprint);
        } else {
            InputUtil.release(MC.options.keySprint);
        }
    }

    // Moves the index past every node the player has already reached and counts
    // the ticks it spends nowhere near the path.
    private void advance(BlockPos here, LocalPlayer player) {
        int last = Math.min(path.size(), index + LOOK_AHEAD);
        for (int i = last - 1; i >= index; i--) {
            if (path.get(i).equals(here)) {
                index = i + 1;
                strayTicks = 0;
                return;
            }
        }
        // A falling player sits in mid air between two nodes every tick.
        if (!player.onGround() || here.equals(path.get(index - 1))) {
            return;
        }
        if (++strayTicks > STRAY_LIMIT) {
            lost = true;
        }
    }

    // Digs out whatever stands in the way of the next node. True whilst it is
    // still working on it.
    private boolean breakInto(PathFinder.Rules rules, BlockPos node) {
        BlockPos blocking = rules.blocking(node);
        if (blocking == null) {
            return false;
        }
        if (!BlockMiner.mine(blocking, true)) {
            lost = true;
        }
        return true;
    }

    private void face(Vec3 target) {
        float want = RotationManager.yawTo(target);
        switch (turn) {
            case CLIENT -> {
                float delta = Mth.wrapDegrees(want - MC.player.getYRot());
                delta = Math.clamp(delta, -TURN_STEP, TURN_STEP);
                MC.player.turn(delta / InputUtil.MOUSE_TURN, 0);
            }
            case SERVER -> RotationManager.request(want, MC.player.getXRot(), RotationPriority.IDLE);
            case NONE -> {
            }
        }
    }

    // Presses whichever movement keys carry the player towards the point given
    // where the view really points.
    private void steer(Vec3 target, LocalPlayer player) {
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4) {
            InputUtil.hold(MC.options.keyUp);
            return;
        }
        dx /= length;
        dz /= length;
        double yaw = Math.toRadians(player.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double forward = dz * cos - dx * sin;
        double left = dx * cos + dz * sin;
        press(MC.options.keyUp, forward > KEY_EDGE);
        press(MC.options.keyDown, forward < -KEY_EDGE);
        press(MC.options.keyLeft, left > KEY_EDGE);
        press(MC.options.keyRight, left < -KEY_EDGE);
    }

    private static void press(KeyMapping key, boolean down) {
        if (down) {
            InputUtil.hold(key);
        } else {
            InputUtil.release(key);
        }
    }

    // True whilst a drop that would hurt sits beside the player and the path is
    // not going down it.
    private boolean nearDrop(PathFinder.Rules rules, BlockPos here, BlockPos node) {
        if (node.getY() < here.getY() || !MC.player.onGround()) {
            return false;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos beside = here.relative(side);
            if (!rules.fitsAt(beside) || rules.canStand(beside)) {
                continue;
            }
            if (deepDrop(rules, beside)) {
                return true;
            }
        }
        return false;
    }

    private boolean deepDrop(PathFinder.Rules rules, BlockPos pos) {
        for (int drop = 1; drop <= safeFall; drop++) {
            if (rules.canStand(pos.below(drop))) {
                return false;
            }
        }
        return true;
    }
}
