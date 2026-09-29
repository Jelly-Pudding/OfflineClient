package com.jellypudding.offlineclient.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

// The blocks around a clicked block that an area pattern covers. The pattern lies in a plane
// that is either level with the ground or stands across the way you look into the block.
// Every offset counts sideways first then across the plane and last through it.
public enum BreakArea {
    SINGLE, SIDE, BOTH_SIDES, PLUS, SQUARE, SQUARE_AND_POLES, ROUNDED_CUBE, CUBE;

    // The blocks the pattern adds around the centre. The centre is never one of them. Ahead
    // must point across the ground and right and left are as seen looking that way.
    public List<BlockPos> around(BlockPos centre, Direction ahead, boolean flat, boolean right) {
        Direction side = ahead.getClockWise();
        Direction across = flat ? ahead : Direction.UP;
        Direction through = flat ? Direction.UP : ahead;
        List<BlockPos> blocks = new ArrayList<>();
        for (int sideways = -1; sideways <= 1; sideways++) {
            for (int up = -1; up <= 1; up++) {
                for (int deep = -1; deep <= 1; deep++) {
                    boolean centreItself = sideways == 0 && up == 0 && deep == 0;
                    if (!centreItself && covers(sideways, up, deep, right)) {
                        blocks.add(centre.relative(side, sideways).relative(across, up).relative(through, deep));
                    }
                }
            }
        }
        return blocks;
    }

    private boolean covers(int sideways, int up, int deep, boolean right) {
        int inPlane = Math.abs(sideways) + Math.abs(up);
        return switch (this) {
            case SINGLE -> false;
            case SIDE -> up == 0 && deep == 0 && sideways == (right ? 1 : -1);
            case BOTH_SIDES -> up == 0 && deep == 0;
            case PLUS -> deep == 0 && inPlane == 1;
            case SQUARE -> deep == 0;
            case SQUARE_AND_POLES -> deep == 0 || inPlane == 0;
            case ROUNDED_CUBE -> deep == 0 || inPlane <= 1;
            case CUBE -> true;
        };
    }
}
