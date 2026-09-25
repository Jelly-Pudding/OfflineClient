package com.jellypudding.offlineclient.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

// Two corners of a box marked one press at a time.
public final class CornerPicker {

    private BlockPos first;
    private BlockPos second;

    // The first free corner takes the spot. A module decides what a third press does.
    public void mark(BlockPos pos) {
        if (first == null) {
            first = pos;
        } else {
            second = pos;
        }
    }

    public void undoSecond() {
        second = null;
    }

    public void clear() {
        first = null;
        second = null;
    }

    public boolean started() {
        return first != null;
    }

    public boolean done() {
        return second != null;
    }

    public BlockPos first() {
        return first;
    }

    public BlockPos second() {
        return second;
    }

    // What the player is asked for next. Null once both corners are down.
    public String prompt() {
        if (first == null) {
            return "pick a corner";
        }
        return second == null ? "pick the other corner" : null;
    }

    // Blocks inside the box with both edges counted.
    public long volume() {
        return (long) (Math.abs(first.getX() - second.getX()) + 1)
            * (Math.abs(first.getY() - second.getY()) + 1)
            * (Math.abs(first.getZ() - second.getZ()) + 1);
    }

    // The lone first block until the second corner closes the box.
    public AABB box() {
        return second == null ? new AABB(first) : AABB.encapsulatingFullBlocks(first, second);
    }
}
