package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

// Two corners of a box marked one press at a time.
public final class CornerPicker {

    // The colour of the marks in the two corners.
    public static final int COLOR = 0xFFFFD040;

    // A corner mark sits this far inside its block and the box this far outside the blocks.
    private static final double MARK_INSET = 0.3;
    private static final double BOX_MARGIN = 0.005;

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

    // True whilst the closed box holds no more blocks than the limit. A larger box is turned down
    // in chat and its second corner is taken back.
    public boolean withinLimit(int limit) {
        long volume = volume();
        if (volume <= limit) {
            return true;
        }
        ChatUtil.error("That box holds " + volume + " blocks and the limit is " + limit + ". Pick a smaller one.");
        second = null;
        return false;
    }

    // True for a block inside the closed box with both edges counted.
    public boolean contains(BlockPos pos) {
        return BoundingBox.fromCorners(first, second).isInside(pos);
    }

    // The lone first block until the second corner closes the box.
    private AABB box() {
        return second == null ? new AABB(first) : AABB.encapsulatingFullBlocks(first, second);
    }

    // The box in its style and once it is closed a mark in each corner.
    public void draw(DrawBatch batch, BoxStyle style) {
        if (first == null) {
            return;
        }
        style.draw(batch, box().inflate(BOX_MARGIN), true);
        if (second != null) {
            batch.outlineBox(new AABB(first).deflate(MARK_INSET), COLOR, true);
            batch.outlineBox(new AABB(second).deflate(MARK_INSET), COLOR, true);
        }
    }
}
