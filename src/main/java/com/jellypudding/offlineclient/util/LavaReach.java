package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.LavaFluid;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

// Where lava poured at a spot could ever run. The guess is wide on purpose. Flowing lava
// falls wherever it can and spreads sideways only once it cannot. The source does both.
// Vanilla picks the sides that lead downhill soonest and here every side counts.
public final class LavaReach {

    private static final Minecraft MC = OfflineClient.MC;

    // A flowing block beside this many sources spreads sideways whilst it falls.
    private static final int SIDE_SOURCES = 3;

    // A flow that could cover more blocks than this counts as reaching everything.
    private static final int MAX_CELLS = 4096;

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
        int dropOff = ((LavaFluid) Fluids.LAVA).getDropOff(MC.level);
        int floor = Mth.floor(box.minY);
        Map<BlockPos, Integer> levels = new HashMap<>();
        ArrayDeque<Flow> open = new ArrayDeque<>();
        offer(levels, open, new Flow(spot, FluidState.AMOUNT_FULL, 0));
        while (!open.isEmpty()) {
            if (levels.size() > MAX_CELLS) {
                return true;
            }
            Flow flow = open.poll();
            BlockPos pos = flow.pos();
            if (box.intersects(pos)) {
                return true;
            }
            if (flow.step() >= steps) {
                continue;
            }
            int step = flow.step() + 1;
            BlockPos below = pos.below();
            boolean falls = holdsLava(below);
            if (falls && below.getY() >= floor) {
                offer(levels, open, new Flow(below, FluidState.AMOUNT_FULL, step));
            }
            if (falls && !pos.equals(spot) && sourcesBeside(pos) < SIDE_SOURCES) {
                continue;
            }
            int side = flow.level() - dropOff;
            if (side <= 0) {
                continue;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = pos.relative(direction);
                if (holdsLava(next)) {
                    offer(levels, open, new Flow(next, side, step));
                }
            }
        }
        return false;
    }

    // Keeps the highest level that reaches a block. The queue runs in step order and a
    // later arrival only goes further when it comes in higher.
    private static void offer(Map<BlockPos, Integer> levels, ArrayDeque<Flow> open, Flow flow) {
        if (levels.getOrDefault(flow.pos(), 0) < flow.level()) {
            levels.put(flow.pos(), flow.level());
            open.add(flow);
        }
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
