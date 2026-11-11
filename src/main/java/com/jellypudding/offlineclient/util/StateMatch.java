package com.jellypudding.offlineclient.util;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.List;

// How a block that stands compares with the state a build wants. Only what the placing click
// decides is compared. Fence arms and stair corners and anything else the neighbours settle
// follows by itself. What a later click or redstone changes such as an open door is left alone.
public final class StateMatch {

    public enum Fit {
        // The block is the one wanted the way it is wanted.
        RIGHT,
        // The right block with fewer of what it stacks such as one candle of three. Another
        // click with the same item adds to it.
        SHORT,
        // The right block bar a detail the neighbours can force such as the hinge of a door.
        NEAR,
        // Another block or the right one facing the wrong way.
        WRONG;

        // True for a block that needs neither breaking nor another click.
        public boolean settled() {
            return this == RIGHT || this == NEAR;
        }
    }

    // The click alone decides these.
    private static final List<Property<?>> CLICKED = List.of(BlockStateProperties.FACING,
        BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.FACING_HOPPER, BlockStateProperties.AXIS,
        BlockStateProperties.HORIZONTAL_AXIS, BlockStateProperties.HALF, BlockStateProperties.ATTACH_FACE,
        BlockStateProperties.ROTATION_16, BlockStateProperties.ORIENTATION, BlockStateProperties.VERTICAL_DIRECTION,
        BlockStateProperties.HANGING);

    // The click decides these unless the blocks around force them.
    private static final List<Property<?>> FORCED = List.of(BlockStateProperties.DOOR_HINGE,
        BlockStateProperties.CHEST_TYPE, BlockStateProperties.BELL_ATTACHMENT);

    // Each click with the same item adds one.
    private static final List<IntegerProperty> STACKED = List.of(BlockStateProperties.LAYERS,
        BlockStateProperties.CANDLES, BlockStateProperties.PICKLES, BlockStateProperties.EGGS,
        BlockStateProperties.FLOWER_AMOUNT, BlockStateProperties.SEGMENT_AMOUNT);

    private StateMatch() {
    }

    // How the block that stands compares with the wanted one. With strict off a right block
    // facing the wrong way or holding too many still counts as right.
    public static Fit fit(BlockState stands, BlockState wanted, boolean strict) {
        if (stands.getBlock() != wanted.getBlock()) {
            return Fit.WRONG;
        }
        boolean fewer = false;
        for (IntegerProperty stacked : STACKED) {
            if (wanted.hasProperty(stacked)) {
                int difference = stands.getValue(stacked) - wanted.getValue(stacked);
                if (difference > 0 && strict) {
                    return Fit.WRONG;
                }
                fewer |= difference < 0;
            }
        }
        if (wanted.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType has = stands.getValue(BlockStateProperties.SLAB_TYPE);
            SlabType wants = wanted.getValue(BlockStateProperties.SLAB_TYPE);
            if (has != wants && wants == SlabType.DOUBLE) {
                fewer = true;
            } else if (has != wants && strict) {
                return Fit.WRONG;
            }
        }
        if (strict && differs(stands, wanted, CLICKED)) {
            return Fit.WRONG;
        }
        if (fewer) {
            return Fit.SHORT;
        }
        return differs(stands, wanted, FORCED) ? Fit.NEAR : Fit.RIGHT;
    }

    private static boolean differs(BlockState stands, BlockState wanted, List<Property<?>> properties) {
        for (Property<?> property : properties) {
            if (wanted.hasProperty(property) && !stands.getValue(property).equals(wanted.getValue(property))) {
                return true;
            }
        }
        return false;
    }

    // True for the half of a bed or a door or a tall plant that appears when the other half is
    // placed. It is never clicked into place itself.
    public static boolean comesWithOtherHalf(BlockState wanted) {
        return wanted.getOptionalValue(BlockStateProperties.BED_PART).orElse(null) == BedPart.HEAD
            || wanted.getOptionalValue(BlockStateProperties.DOUBLE_BLOCK_HALF).orElse(null) == DoubleBlockHalf.UPPER;
    }

    // The item that places the block. Null for a block nothing in the game places by hand
    // such as fire or water or the head of a piston.
    public static BlockItem itemFor(BlockState wanted) {
        Item item = wanted.getBlock().asItem();
        return item instanceof BlockItem blockItem ? blockItem : null;
    }
}
