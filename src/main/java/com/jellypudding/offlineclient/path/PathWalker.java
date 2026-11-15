package com.jellypudding.offlineclient.path;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.PathfinderModule;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

// Drives the player along a finished path one tick at a time.
public final class PathWalker {

    // How the view is pointed at the next node.
    public enum Turn { CLIENT, NONE }

    private static final Minecraft MC = OfflineClient.MC;

    // How far ahead a node may be and still count as the one the player is on.
    private static final int LOOK_AHEAD = 8;

    // How many steps ahead the run of a walk is measured for whatever adds pace.
    private static final int RUN_SIGHT = 16;

    // Ticks a walk may steer at one node without reaching it. The slowest ground there is takes
    // a quarter of this for a block. Past it the node is out of reach from where the player is.
    private static final int STALL_LIMIT = 100;

    // Ticks off the path or held against a wall on the node before. Then a new search is asked for.
    private static final int STRAY_LIMIT = 30;

    // The most degrees a client side turn moves in one tick.
    private static final float TURN_STEP = 30;

    // How closely the keys have to line up with the way to the next node.
    private static final double KEY_EDGE = 0.35;

    // Ticks a floor may refuse to go down before the walk looks for another way.
    private static final int LAY_PATIENCE = 20;

    // The walk that holds the sneak key down. Null whilst none does.
    private static PathWalker sneaker;

    // The steps the walk ticked last has ahead of its player before it goes down and the tick
    // they were counted on.
    private static int runTick = Integer.MIN_VALUE;
    private static int levelRun;

    private List<BlockPos> path = List.of();
    private int index;
    private int strayTicks;
    // Ticks of pushing into something from the node before with nothing left to dig.
    private int pushTicks;
    // Ticks of steering at the same node.
    private int stallTicks;
    private boolean lost;
    // The node the walk steered at in vain as it was lost. Null when it was lost another way.
    private BlockPos walled;
    private boolean holding;
    private int layTicks;
    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();

    private Turn turn = Turn.CLIENT;
    private boolean sprint = true;

    public PathWalker turn(Turn mode) {
        this.turn = mode;
        return this;
    }

    public PathWalker sprint(boolean allowed) {
        this.sprint = allowed;
        return this;
    }

    public void follow(List<BlockPos> nodes) {
        path = nodes;
        index = nodes.isEmpty() ? 0 : 1;
        strayTicks = 0;
        pushTicks = 0;
        stallTicks = 0;
        layTicks = 0;
        lost = false;
        walled = null;
    }

    // The hotbar slot with a block the Pathfinder row lists that makes a floor at the spot.
    // Minus one when there is none.
    static int blockSlot(BlockPos floor) {
        return BlockUtil.findBlockSlot(block -> PathfinderModule.lays(block) && BlockUtil.buildsAt(block, floor));
    }

    // Asks something with the sneak a walk holds by itself lifted. Jesus takes a held sneak
    // for a wish to sink and a walk that creeps along an edge wishes no such thing. A sneak
    // the player holds stays as it is.
    static boolean unsneaked(BooleanSupplier question) {
        KeyMapping key = MC.options.keyShift;
        if (sneaker == null || !key.isDown() || InputUtil.physicallyHeld(key)) {
            return question.getAsBoolean();
        }
        InputUtil.release(key);
        try {
            return question.getAsBoolean();
        } finally {
            InputUtil.hold(key);
        }
    }

    public boolean arrived() {
        return index >= path.size();
    }

    // The node this many steps past the one being steered at. The last one when the path ends sooner.
    public BlockPos ahead(int steps) {
        return path.get(Math.min(path.size() - 1, index + steps));
    }

    public BlockPos end() {
        return path.getLast();
    }

    // Swaps everything past a node still ahead for a way that begins on it. False once the
    // player is past that node.
    public boolean rejoin(List<BlockPos> onward) {
        int from = path.lastIndexOf(onward.getFirst());
        if (from < index) {
            return false;
        }
        List<BlockPos> joined = new ArrayList<>(path.subList(0, from));
        joined.addAll(onward);
        path = List.copyOf(joined);
        return true;
    }

    // True once the player has strayed or the world has changed under the path.
    public boolean lost() {
        return lost;
    }

    // The spot a lost walk could not get into though every rule said it could. Null when the
    // walk was lost another way.
    public BlockPos walled() {
        return walled;
    }

    // The steps a walk of this tick has ahead before it next goes down. A pace above a plain walk
    // skips a gap one block wide and lands past a ledge. No walk leaves any pace free.
    public static int levelRun() {
        return steering() ? levelRun : Integer.MAX_VALUE;
    }

    // True on a tick a walk steers the player and at the end of that tick once the count has
    // moved on by one.
    public static boolean steering() {
        return MC.player != null && (MC.player.tickCount == runTick || MC.player.tickCount == runTick + 1);
    }

    public void stop() {
        releaseKeys();
        path = List.of();
        index = 0;
        strayTicks = 0;
        pushTicks = 0;
        stallTicks = 0;
        lost = false;
        walled = null;
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
        InputUtil.release(MC.options.keySprint);
        sneak(false);
        holding = false;
    }

    private void sneak(boolean down) {
        press(MC.options.keyShift, down);
        if (down) {
            sneaker = this;
        } else if (sneaker == this) {
            sneaker = null;
        }
    }

    // Called once a tick whilst a path is being walked.
    public void tick(PathFinder.Rules rules) {
        LocalPlayer player = MC.player;
        if (player == null || arrived()) {
            releaseKeys();
            return;
        }
        if (PathfinderModule.pausesWalk()) {
            stand(player, false);
            return;
        }
        BlockPos here = PathFinder.standingAt(player);
        advance(rules, here, player);
        if (arrived()) {
            releaseKeys();
            return;
        }
        RouteView.walking(path, index);
        measureRun(player);

        BlockPos prev = path.get(index - 1);
        BlockPos node = path.get(index);
        if (!rules.canStep(prev, node)) {
            lost = true;
        }
        if (breakInto(rules, prev, node)) {
            pushTicks = 0;
            stallTicks = 0;
            releaseKeys();
            return;
        }
        boolean climbing = player.onClimbable();
        BlockPos floor = rules.missingFloor(node);
        // A sneak holds the player on the edge until the floor ahead is down. Jesus drops
        // whoever sneaks and a walk on a liquid it holds up never does.
        boolean afloat = rules.afloat(here) || rules.afloat(node);
        boolean sneaks = !climbing && !afloat && (floor != null || nearDrop(rules, here, node));
        if (floor == null) {
            layTicks = 0;
        } else if (!lays(floor) || afloat) {
            stallTicks = 0;
            stand(player, sneaks);
            return;
        }

        Vec3 target = Vec3.atBottomCenterOf(node);
        face(target);
        steer(target, player);
        holding = true;
        // A wall no rule names holds the player on the node before. Anything else that keeps the
        // node out of reach shows as a walk that steers at it and never gets there.
        boolean pushes = player.onGround() && player.horizontalCollision && here.equals(prev);
        if (pushes && ++pushTicks > STRAY_LIMIT || ++stallTicks > STALL_LIMIT) {
            lost = true;
            walled = node;
        }

        // Step lifts nobody who sneaks.
        boolean walksUp = !sneaks && !climbing && stepsUp(rules, here, node);
        if (node.getY() > here.getY() && (player.onGround() || climbing) && !walksUp) {
            InputUtil.hold(MC.options.keyJump);
        } else if (player.isInWater() && node.getY() >= here.getY()) {
            InputUtil.hold(MC.options.keyJump);
        } else {
            InputUtil.release(MC.options.keyJump);
        }
        // Forward is what climbs a ladder. Let go of it on the way straight down one. A step off
        // to the side out of a vine that hangs over the ground still needs it.
        if (climbing && node.getY() < here.getY() && !across(here, node)) {
            InputUtil.release(MC.options.keyUp);
        }

        // A player who lets go inside a vine that hangs free sinks through it. A sneak holds the
        // height for the step out of it to the side.
        sneak(sneaks || climbing && !player.onGround() && across(here, node) && node.getY() >= here.getY());

        if (sprint && !player.isInWater() && node.getY() >= here.getY()) {
            InputUtil.hold(MC.options.keySprint);
        } else {
            InputUtil.release(MC.options.keySprint);
        }
    }

    // Counts the steps ahead before the path goes down. A path through water has none. Nothing
    // asks about a run longer than the sight.
    private void measureRun(LocalPlayer player) {
        int last = Math.min(path.size(), index + RUN_SIGHT);
        int level = 0;
        while (index + level < last && path.get(index + level).getY() >= path.get(index + level - 1).getY()) {
            level++;
        }
        // A swimmer bobs out of the water and back in. Pace added in the air between would
        // throw it from side to side all the way across.
        levelRun = wet(path.get(index)) || wet(path.get(index - 1)) ? 0 : level;
        runTick = player.tickCount;
    }

    private static boolean wet(BlockPos node) {
        return !MC.level.getFluidState(node).isEmpty();
    }

    // Moves the index past every node the player has already reached and counts
    // the ticks it spends nowhere near the path. A node still waiting for its floor is
    // not reached by a player who hangs over it or jumps through it.
    private void advance(PathFinder.Rules rules, BlockPos here, LocalPlayer player) {
        int last = Math.min(path.size(), index + LOOK_AHEAD);
        for (int i = last - 1; i >= index; i--) {
            if (path.get(i).equals(here) && rules.missingFloor(here) == null) {
                index = i + 1;
                strayTicks = 0;
                pushTicks = 0;
                stallTicks = 0;
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

    // Digs out whatever stands in the way of the step from prev to the next node. True whilst
    // it is still working on it.
    private boolean breakInto(PathFinder.Rules rules, BlockPos prev, BlockPos node) {
        BlockPos blocking = rules.blocking(prev, node);
        if (blocking == null) {
            return false;
        }
        if (!BlockMiner.mine(blocking, true)) {
            lost = true;
        }
        return true;
    }

    // True when the rise to the node is one the player walks up without a jump. Straight
    // up or out of water or off a ladder still takes one.
    private static boolean stepsUp(PathFinder.Rules rules, BlockPos here, BlockPos node) {
        return across(here, node) && node.getY() - here.getY() <= rules.stepUp()
            && MC.player.onGround() && !MC.player.isInWater();
    }

    // True when the node lies in another column than the player.
    private static boolean across(BlockPos here, BlockPos node) {
        return node.getX() != here.getX() || node.getZ() != here.getZ();
    }

    // Lays the floor the next node wants from the hotbar. A floor that goes where the player
    // stands waits for the jump to lift them clear. False whilst the hands are busy and
    // the floor has to wait for them.
    private boolean lays(BlockPos floor) {
        if (handsBusy()) {
            return false;
        }
        if (BlockUtil.intersectsPlayer(floor)) {
            return true;
        }
        int slot = blockSlot(floor);
        if (slot == -1 || !BlockUtil.placeFrom(slots, slot, floor, true) && ++layTicks > LAY_PATIENCE) {
            lost = true;
        }
        return true;
    }

    // A change of slot would stop a meal or a drawn bow and ShulkerPacker counts the stacks
    // it packs.
    private static boolean handsBusy() {
        return MC.player.isUsingItem() || Hands.slotsTaken();
    }

    // Stands still and keeps a sneak that holds an edge. A swimmer who lets go of jump sinks.
    private void stand(LocalPlayer player, boolean sneaks) {
        releaseKeys();
        boolean swims = player.isInWater();
        if (sneaks) {
            sneak(true);
        }
        if (swims) {
            InputUtil.hold(MC.options.keyJump);
        }
        holding = sneaks || swims;
    }

    private void face(Vec3 target) {
        if (turn == Turn.NONE) {
            return;
        }
        float delta = Mth.wrapDegrees(RotationManager.yawTo(target) - MC.player.getYRot());
        delta = Math.clamp(delta, -TURN_STEP, TURN_STEP);
        MC.player.turn(delta / InputUtil.MOUSE_TURN, 0);
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
            if (rules.fitsAt(beside) && !rules.canStand(beside) && rules.deepDrop(beside)) {
                return true;
            }
        }
        return false;
    }
}
