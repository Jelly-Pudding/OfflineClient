package com.jellypudding.offlineclient.path;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

// Walks to one block at a time and mines it once it is in reach. A block the walk cannot get
// near or the server will not let go of is left alone for a minute and the caller hands over
// another. A block holding back a liquid or sand is left alone the same way. It can pick up what
// each block dropped before it moves on.
public final class MiningTrip {

    private static final Minecraft MC = OfflineClient.MC;

    // Ticks a spot the walker could not reach is left alone.
    private static final int SHUN_TICKS = 1200;

    // Ticks past its break time a block in reach gets before it is given up on. Covers a tool
    // swap and the pause the game takes between two blocks.
    private static final int GIVE_UP_TICKS = 60;

    // How near the walk brings the player to a block before mining starts.
    private static final double GOAL_RADIUS = 3;

    // Drops land within this many blocks of where their block stood.
    private static final double DROP_RADIUS = 2.5;

    // How long after a block breaks its drops are worth going back for.
    private static final int DROP_TICKS = 200;

    private final Trip trip = new Trip();
    private final Cooldowns<BlockPos> shunned = new Cooldowns<>();
    private final Cooldowns<Integer> shunnedDrops = new Cooldowns<>();
    private final Deque<Mined> mined = new ArrayDeque<>();
    private BlockPos target;
    private int miningTicks;
    private ItemEntity drop;
    private boolean collect;
    private boolean warned;

    private record Mined(BlockPos pos, int tick) {
    }

    // Whether a mining bot walks to its blocks or leaves the walking to you.
    public enum Movement { WALK, MANUAL }

    public static EnumSetting<Movement> movementSetting() {
        return new EnumSetting<>("Movement", "Who moves you from block to block.", Movement.WALK)
            .describe(Movement.WALK, "Walks to each block in turn.")
            .describe(Movement.MANUAL, "You do the walking and it mines whatever comes in reach.");
    }

    public MiningTrip() {
        trip.finder().breakBlocks(true);
        trip.walker().turn(PathWalker.Turn.CLIENT);
    }

    // Walks over the items each mined block dropped before the next block is taken.
    public MiningTrip collect(boolean on) {
        collect = on;
        return this;
    }

    public BlockPos target() {
        return target;
    }

    public boolean shuns(BlockPos pos) {
        return shunned.contains(pos);
    }

    // Everything is forgotten for a fresh start.
    public void reset() {
        stop();
        shunned.clear();
        shunnedDrops.clear();
        mined.clear();
        warned = false;
    }

    // Stops walking and mining. The spots it gave up on stay given up on.
    public void stop() {
        BlockMiner.release();
        letGo();
        drop = null;
    }

    // One tick of work. The finder is asked for a block whenever there is none and a block the
    // test turns down is let go. Without walking a block out of reach is let go as well.
    // False whilst there is nothing to do.
    public boolean tick(Predicate<BlockPos> wanted, Supplier<BlockPos> finder, boolean rotate, boolean walk) {
        shunned.tick();
        shunnedDrops.tick();
        if (target != null && !wanted.test(target)) {
            if (walk && collect && !BlockUtil.diggable(target)) {
                mined.addLast(new Mined(target, clock()));
            }
            letGo();
        }
        if (target == null && walk && collect && collectDrop()) {
            return true;
        }
        if (target == null) {
            target = finder.get();
            if (target == null) {
                return false;
            }
        }
        if (unsafe(target)) {
            giveUp();
            if (!warned) {
                warned = true;
                ChatUtil.message("§7Skipped a block that would let a liquid in or drop sand or gravel on you.");
            }
            return true;
        }
        if (BlockUtil.inReach(target)) {
            trip.stop();
            // A block the server keeps refusing comes back every time it breaks.
            int patience = GIVE_UP_TICKS + ServerInfo.pingTicks();
            if (++miningTicks - patience > BlockUtil.breakTicks(target)) {
                giveUp();
                return true;
            }
            BlockMiner.mine(target, rotate);
            return true;
        }
        miningTicks = 0;
        if (!walk) {
            BlockMiner.release();
            letGo();
            return false;
        }
        // The walker digs its own way through. The block mined before is let go only as a walk starts.
        if (!trip.active()) {
            BlockMiner.release();
            trip.start(target, GOAL_RADIUS);
        }
        // A walk that ends short of reach has run into something the walker cannot pass.
        Trip.State state = trip.tick();
        if (state == Trip.State.FAILED || state == Trip.State.ARRIVED) {
            giveUp();
        }
        return true;
    }

    // The nearest block in reach the test wants that is not left alone. For mining without walking.
    public BlockPos nearestInReach(Predicate<BlockPos> wanted) {
        return BlockUtil.nearestWithin(BlockUtil.serverBlockReach(), pos -> !shuns(pos) && wanted.test(pos));
    }

    private void giveUp() {
        shunned.put(target, SHUN_TICKS);
        letGo();
    }

    private void letGo() {
        trip.stop();
        target = null;
        miningTicks = 0;
    }

    // Breaking the block would let lava or water flow in or drop sand or gravel on the player.
    private static boolean unsafe(BlockPos pos) {
        for (Direction side : Direction.values()) {
            // A liquid below never flows up into the gap.
            if (side != Direction.DOWN && !BlockUtil.state(pos.relative(side)).getFluidState().isEmpty()) {
                return true;
            }
        }
        return BlockUtil.state(pos.above()).getBlock() instanceof FallingBlock && fallsOnPlayer(pos);
    }

    // True when the gap and the open blocks below it reach down into the player.
    private static boolean fallsOnPlayer(BlockPos gap) {
        AABB body = MC.player.getBoundingBox();
        BlockPos.MutableBlockPos cursor = gap.mutable();
        while (cursor.getY() >= Mth.floor(body.minY)) {
            if (body.intersects(new AABB(cursor))) {
                return true;
            }
            cursor.move(Direction.DOWN);
            if (!FallingBlock.isFree(BlockUtil.state(cursor))) {
                return false;
            }
        }
        return false;
    }

    // Walks to the nearest item lying by a block mined a moment ago. False when there is none.
    private boolean collectDrop() {
        forgetOldMined();
        if (drop == null || !drop.isAlive()) {
            drop = nearestDrop();
            if (drop == null) {
                return false;
            }
            trip.start(drop.blockPosition(), 0);
        }
        Trip.State state = trip.tick();
        if (state == Trip.State.FAILED || state == Trip.State.ARRIVED) {
            // An item still lying there after the walk cannot be picked up. The bag may be full.
            if (drop.isAlive()) {
                shunnedDrops.put(drop.getId(), SHUN_TICKS);
            }
            drop = null;
            trip.stop();
        }
        return true;
    }

    private ItemEntity nearestDrop() {
        if (mined.isEmpty()) {
            return null;
        }
        Vec3 eye = MC.player.getEyePosition();
        ItemEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Mined block : mined) {
            AABB around = new AABB(block.pos()).inflate(DROP_RADIUS);
            List<ItemEntity> items = MC.level.getEntitiesOfClass(ItemEntity.class, around,
                item -> item.isAlive() && !shunnedDrops.contains(item.getId()));
            for (ItemEntity item : items) {
                double distance = item.distanceToSqr(eye);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = item;
                }
            }
        }
        return best;
    }

    private void forgetOldMined() {
        int now = clock();
        // The clock starts again after a respawn.
        for (Iterator<Mined> it = mined.iterator(); it.hasNext(); ) {
            int tick = it.next().tick();
            if (now < tick || now - tick > DROP_TICKS) {
                it.remove();
            }
        }
    }

    private static int clock() {
        return MC.player.tickCount;
    }
}
