package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.LavaFluid;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

// Where lava poured at a spot runs and how long it takes. Flowing lava falls wherever it
// can and spreads sideways only once it cannot. The safety test guesses wide and lets
// every side count and lets the source do both. The time estimate follows vanilla and
// only spreads towards the sides that lead downhill soonest.
public final class LavaReach {

    private static final Minecraft MC = OfflineClient.MC;

    // A flowing block beside this many sources spreads sideways whilst it falls.
    private static final int SIDE_SOURCES = 3;

    // A flow that could cover more blocks than this counts as reaching everything.
    private static final int MAX_CELLS = 4096;

    // Vanilla's distance for a side with no drop in reach.
    private static final int NO_DROP = 1000;

    // A staircase that drops a block for every block across costs a sideways step and a fall per step.
    private static final int STEPS_PER_STAIR = 2;

    // How a walk ended when it did not run dry.
    private static final int STOPPED = -1;
    private static final int OVERFLOW = -2;

    // A block the lava reaches with its level and the steps it took to get there.
    private record Flow(BlockPos pos, int level, int step) {
    }

    private LavaReach() {
    }

    // True when lava poured at the spot could ever run into any block the box touches.
    public static boolean touches(BlockPos spot, AABB box) {
        return touches(spot, box, Integer.MAX_VALUE);
    }

    // The same for lava taken back after the given number of flow steps. In one step lava
    // falls a block or spreads one to the side. Lava never climbs and a block below the
    // box cannot lead back up to it.
    public static boolean touches(BlockPos spot, AABB box, int steps) {
        int end = walk(spot, steps, Mth.floor(box.minY), false, box::intersects);
        return end == STOPPED || end == OVERFLOW;
    }

    // Flow steps until lava poured at the spot stops reaching new blocks. Minus one for a
    // flow too big to follow.
    public static int flowSteps(BlockPos spot) {
        int end = walk(spot, Integer.MAX_VALUE, MC.level.getMinY(), true, pos -> false);
        return end == OVERFLOW ? -1 : end;
    }

    // Flow steps down a staircase that drops a block a step.
    public static int stairSteps(int drop) {
        return STEPS_PER_STAIR * Math.max(0, drop);
    }

    // Flow steps for lava poured on the top step of a staircase. It runs down the stairs and
    // off the side of the bottom step or falls straight off the side of the top one. The
    // longer way decides. Each fall ends on the ground found under its step.
    public static int staircaseSteps(int top, int bottom, int groundUnderTop, int groundUnderBottom) {
        int downStairs = stairSteps(top - bottom) + 1 + (bottom - groundUnderBottom);
        int offTheTop = 1 + (top - groundUnderTop);
        return Math.max(downStairs, offTheTop);
    }

    // Seconds a flow of this many steps takes at the server's current tick rate.
    public static double seconds(int steps) {
        return (double) steps * Fluids.LAVA.getTickDelay(MC.level) / TickRate.INSTANCE.tps();
    }

    // The height of the first block under the spot that stops falling lava. One below the
    // world when nothing does.
    public static int groundBelow(BlockPos spot) {
        BlockPos.MutableBlockPos cursor = spot.mutable().move(Direction.DOWN);
        while (holdsLava(cursor)) {
            cursor.move(Direction.DOWN);
        }
        return cursor.getY();
    }

    // Runs the flow out from the spot a step at a time. The answer is the last step that
    // reached a new block. A block that passes the stop test ends it at once. A flow grown
    // past the cap ends it too. The floor drops every fall below it.
    private static int walk(BlockPos spot, int steps, int floor, boolean vanillaSides, Predicate<BlockPos> stop) {
        LavaFluid lava = (LavaFluid) Fluids.LAVA;
        int dropOff = lava.getDropOff(MC.level);
        int slopeReach = lava.getSlopeFindDistance(MC.level);
        Map<BlockPos, Integer> levels = new HashMap<>();
        ArrayDeque<Flow> open = new ArrayDeque<>();
        offer(levels, open, new Flow(spot, FluidState.AMOUNT_FULL, 0));
        int last = 0;
        while (!open.isEmpty()) {
            if (levels.size() > MAX_CELLS) {
                return OVERFLOW;
            }
            Flow flow = open.poll();
            BlockPos pos = flow.pos();
            if (stop.test(pos)) {
                return STOPPED;
            }
            if (flow.step() >= steps) {
                continue;
            }
            int step = flow.step() + 1;
            BlockPos below = pos.below();
            boolean falls = holdsLava(below);
            if (falls && below.getY() >= floor && offer(levels, open, new Flow(below, FluidState.AMOUNT_FULL, step))) {
                last = step;
            }
            boolean wideSource = !vanillaSides && pos.equals(spot);
            if (falls && !wideSource && sourcesBeside(pos) < SIDE_SOURCES) {
                continue;
            }
            int side = flow.level() - dropOff;
            if (side <= 0) {
                continue;
            }
            for (Direction direction : sides(pos, vanillaSides, slopeReach)) {
                if (offer(levels, open, new Flow(pos.relative(direction), side, step))) {
                    last = step;
                }
            }
        }
        return last;
    }

    // Keeps the highest level that reaches a block. The queue runs in step order and a
    // later arrival only goes further when it comes in higher. True for a block reached
    // for the first time.
    private static boolean offer(Map<BlockPos, Integer> levels, ArrayDeque<Flow> open, Flow flow) {
        Integer known = levels.get(flow.pos());
        if (known != null && known >= flow.level()) {
            return false;
        }
        levels.put(flow.pos(), flow.level());
        open.add(flow);
        return known == null;
    }

    // The sides lava leaves a block by. Vanilla keeps only those with the nearest drop and
    // every open side ties when none is in reach.
    private static List<Direction> sides(BlockPos pos, boolean vanillaSides, int slopeReach) {
        List<Direction> chosen = new ArrayList<>();
        int best = NO_DROP;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos next = pos.relative(direction);
            if (!holdsLava(next)) {
                continue;
            }
            if (!vanillaSides) {
                chosen.add(direction);
                continue;
            }
            int distance = dropsBelow(next) ? 0 : dropDistance(next, direction.getOpposite(), 1, slopeReach);
            if (distance < best) {
                chosen.clear();
                best = distance;
            }
            if (distance == best) {
                chosen.add(direction);
            }
        }
        return chosen;
    }

    // Blocks across to the nearest drop the way vanilla searches. It never turns back the
    // way it came and gives up past its reach.
    private static int dropDistance(BlockPos pos, Direction cameFrom, int depth, int reach) {
        int best = NO_DROP;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (direction == cameFrom) {
                continue;
            }
            BlockPos next = pos.relative(direction);
            if (!holdsLava(next)) {
                continue;
            }
            if (dropsBelow(next)) {
                return depth;
            }
            if (depth < reach) {
                best = Math.min(best, dropDistance(next, direction.getOpposite(), depth + 1, reach));
            }
        }
        return best;
    }

    // Lava in this block could fall into the one below. Lava already there counts.
    private static boolean dropsBelow(BlockPos pos) {
        BlockPos below = pos.below();
        return holdsLava(below) || MC.level.getFluidState(below).is(FluidTags.LAVA);
    }

    // The test vanilla runs before lava moves into a block. A lava source is already full.
    private static boolean holdsLava(BlockPos pos) {
        if (!MC.level.isInWorldBounds(pos)) {
            return false;
        }
        BlockState state = BlockUtil.state(pos);
        if (state.getFluidState().isSourceOfType(Fluids.LAVA)) {
            return false;
        }
        if (state.getBlock() instanceof LiquidBlockContainer container) {
            return container.canPlaceLiquid(null, MC.level, pos, state, Fluids.LAVA);
        }
        return state.is(BlockTags.WASHED_AWAY_BY_FLUIDS);
    }

    private static int sourcesBeside(BlockPos pos) {
        int sources = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (MC.level.getFluidState(pos.relative(direction)).isSourceOfType(Fluids.LAVA)) {
                sources++;
            }
        }
        return sources;
    }
}
