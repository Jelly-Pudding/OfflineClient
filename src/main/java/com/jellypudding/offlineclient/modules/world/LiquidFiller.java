package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.AreaPlacer;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.Comparator;
import java.util.List;

public final class LiquidFiller extends AreaPlacer {

    private static final int TARGET_COLOR = 0xFF60D0FF;

    public enum Shape { SPHERE, UNIFORM_CUBE }

    public enum ListMode { WHITELIST, BLACKLIST }

    public enum Order { CLOSEST, FURTHEST, TOP_DOWN, BOTTOM_UP }

    private final BoolSetting water = new BoolSetting("Water",
        "Fills water.", true);
    private final BoolSetting lava = new BoolSetting("Lava",
        "Fills lava.", true);
    private final BoolSetting flowing = new BoolSetting("Flowing",
        "Also fill flowing liquid and not just the source blocks.", false);
    private final EnumSetting<Shape> shape = new EnumSetting<>("Shape",
        "The region searched for liquid.", Shape.SPHERE)
        .describe(Shape.SPHERE, "A ball of the given radius around you.")
        .describe(Shape.UNIFORM_CUBE, "A cube of the rounded down range on every side.");
    private final EnumSetting<ListMode> listMode = new EnumSetting<>("List mode",
        "What the block list means.", ListMode.WHITELIST)
        .describe(ListMode.WHITELIST, "Only the listed blocks are used as filler.")
        .describe(ListMode.BLACKLIST, "Any block but the listed ones is used as filler.");
    private final EnumSetting<Order> order = new EnumSetting<>("Order",
        "Which block gets filled first.", Order.CLOSEST)
        .describe(Order.CLOSEST, "The nearest liquid first.")
        .describe(Order.FURTHEST, "The furthest liquid first.")
        .describe(Order.TOP_DOWN, "The top layer first and down from there.")
        .describe(Order.BOTTOM_UP, "The bottom layer first and up from there.");

    public LiquidFiller() {
        super("LiquidFiller", "Fills the water and lava around you with solid blocks.",
            "The blocks the list applies to. Click to pick them.",
            List.of(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.DIRT, Blocks.STONE,
                Blocks.DEEPSLATE, Blocks.NETHERRACK, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE),
            "Outline the liquid waiting to be filled.", 1, TARGET_COLOR);
        addSettings(shape, range, wallsRange, water, lava, flowing, blocks, listMode,
            order, perTick, delay, rotate, render);
        searchTags("lava", "water", "fill");
    }

    @Override
    protected void collect() {
        for (BlockPos pos : scan()) {
            if (!isWanted(pos) || BlockUtil.intersectsPlayer(pos)) {
                continue;
            }
            if (!mc.level.isUnobstructed(Blocks.STONE.defaultBlockState(), pos, CollisionContext.empty())) {
                continue;
            }
            if (!inReach(pos)) {
                continue;
            }
            targets.add(pos.immutable());
        }
        sort();
    }

    // The cube shape reaches into corners the sphere scan would miss.
    private Iterable<BlockPos> scan() {
        if (shape.is(Shape.SPHERE)) {
            return BlockUtil.positionsWithin(range.getValue());
        }
        int r = (int) Math.floor(range.getValue());
        BlockPos centre = mc.player.blockPosition();
        return BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r));
    }

    // A cube is measured by the largest step on any axis from the block you stand in.
    @Override
    protected boolean withinRange(BlockPos pos, double reach) {
        if (shape.is(Shape.SPHERE)) {
            return super.withinRange(pos, reach);
        }
        BlockPos centre = mc.player.blockPosition();
        int step = Math.max(Math.abs(pos.getX() - centre.getX()),
            Math.max(Math.abs(pos.getY() - centre.getY()), Math.abs(pos.getZ() - centre.getZ())));
        return step <= Math.floor(reach);
    }

    @Override
    protected boolean allowed(Block block) {
        return blocks.contains(block) == listMode.is(ListMode.WHITELIST);
    }

    @Override
    protected boolean placeOn(BlockPos pos) {
        return BlockUtil.placeAny(pos, rotate.isOn(), true);
    }

    private boolean isWanted(BlockPos pos) {
        FluidState fluid = mc.level.getFluidState(pos);
        if (fluid.isEmpty() || (!flowing.isOn() && !fluid.isSource())) {
            return false;
        }
        if (!BlockUtil.isReplaceable(pos)) {
            return false;
        }
        if (fluid.is(FluidTags.WATER)) {
            return water.isOn();
        }
        return fluid.is(FluidTags.LAVA) && lava.isOn();
    }

    private void sort() {
        switch (order.getValue()) {
            case CLOSEST -> targets.sort(
                Comparator.comparingDouble((BlockPos pos) -> BlockUtil.distanceTo(pos)));
            case FURTHEST -> targets.sort(
                Comparator.comparingDouble((BlockPos pos) -> BlockUtil.distanceTo(pos)).reversed());
            case TOP_DOWN -> targets.sort(
                Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed());
            case BOTTOM_UP -> targets.sort(
                Comparator.comparingInt((BlockPos pos) -> pos.getY()));
        }
    }
}
