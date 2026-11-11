package com.jellypudding.offlineclient.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

// Buttons and levers and what they power once pressed or pulled.
public final class Switches {

    private Switches() {
    }

    // True when the button or lever at the spot powers the target. It powers every block touching
    // it and through the block it sits on every block touching that one. Glass and the like pass
    // nothing on.
    public static boolean powers(BlockGetter level, BlockPos pos, BlockState state, BlockPos target) {
        if (pos.distManhattan(target) == 1) {
            return true;
        }
        BlockPos base = pos.relative(facing(state).getOpposite());
        return base.distManhattan(target) == 1 && level.getBlockState(base).isRedstoneConductor(level, base);
    }

    // The way the button or lever points away from the block it sits on.
    private static Direction facing(BlockState state) {
        return switch (state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE)) {
            case FLOOR -> Direction.UP;
            case CEILING -> Direction.DOWN;
            case WALL -> state.getValue(HorizontalDirectionalBlock.FACING);
        };
    }
}
