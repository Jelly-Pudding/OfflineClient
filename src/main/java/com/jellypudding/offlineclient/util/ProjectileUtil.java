package com.jellypudding.offlineclient.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public final class ProjectileUtil {

    // The share of its speed a projectile keeps each tick in open air.
    public static final double AIR_DRAG = 0.99;

    // Blocks per tick an arrow loses downward.
    public static final double ARROW_GRAVITY = 0.05;

    // Blocks per tick an arrow leaves a fully drawn bow and a crossbow at.
    public static final double BOW_SPEED = 3;
    public static final double CROSSBOW_SPEED = 3.15;

    // Blocks per tick a thrown pearl or snowball loses downward.
    public static final double THROWN_GRAVITY = 0.03;

    private ProjectileUtil() {
    }

    public static boolean inBubbles(Entity projectile) {
        return column(projectile) != null;
    }

    // Ocean magma pulls columns down and never holds a pearl.
    public static boolean inStasis(Entity pearl) {
        BlockState column = column(pearl);
        return column != null && !column.getValue(BubbleColumnBlock.DRAG_DOWN);
    }

    private static BlockState column(Entity projectile) {
        BlockPos pos = projectile.blockPosition();
        for (BlockPos at : List.of(pos, pos.below())) {
            BlockState state = BlockUtil.state(at);
            if (state.is(Blocks.BUBBLE_COLUMN)) {
                return state;
            }
        }
        return null;
    }
}
