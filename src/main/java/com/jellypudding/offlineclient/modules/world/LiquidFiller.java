package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.AreaPlacer;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.Comparator;
import java.util.List;

public final class LiquidFiller extends AreaPlacer {

    private static final int TARGET_COLOR = 0xFF60D0FF;

    public enum Order { CLOSEST, FURTHEST, TOP_DOWN, BOTTOM_UP }

    private final BoolSetting water = new BoolSetting("Water",
        "Fills water.", true);
    private final BoolSetting lava = new BoolSetting("Lava",
        "Fills lava.", true);
    private final BoolSetting flowing = new BoolSetting("Flowing",
        "Also fill flowing liquid and not just the source blocks.", false);
    private final EnumSetting<Order> order = new EnumSetting<>("Order",
        "Which block gets filled first.", Order.CLOSEST)
        .describe(Order.CLOSEST, "The nearest liquid first.")
        .describe(Order.FURTHEST, "The furthest liquid first.")
        .describe(Order.TOP_DOWN, "The top layer first and down from there.")
        .describe(Order.BOTTOM_UP, "The bottom layer first and up from there.");

    public LiquidFiller() {
        super("LiquidFiller", "Fills the water and lava around you with solid blocks.",
            "Blocks allowed to be used as filler. Click to pick them.",
            List.of(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.DIRT, Blocks.STONE,
                Blocks.DEEPSLATE, Blocks.NETHERRACK, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE),
            "Outline the liquid waiting to be filled.", 1, TARGET_COLOR);
        addSettings(range, water, lava, flowing, blocks, order, perTick, delay, rotate, render);
        searchTags("lava", "water", "fill");
    }

    @Override
    protected void collect() {
        // positionsWithin already hands them back nearest first.
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (!isWanted(pos) || BlockUtil.intersectsPlayer(pos)) {
                continue;
            }
            if (!mc.level.isUnobstructed(Blocks.STONE.defaultBlockState(), pos, CollisionContext.empty())) {
                continue;
            }
            targets.add(pos.immutable());
        }
        sort();
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
            case CLOSEST -> {
                // Already nearest first.
            }
            case FURTHEST -> targets.sort(
                Comparator.comparingDouble((BlockPos pos) -> BlockUtil.distanceTo(pos)).reversed());
            case TOP_DOWN -> targets.sort(
                Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed());
            case BOTTOM_UP -> targets.sort(
                Comparator.comparingInt((BlockPos pos) -> pos.getY()));
        }
    }
}
