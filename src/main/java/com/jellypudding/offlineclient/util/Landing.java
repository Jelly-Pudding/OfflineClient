package com.jellypudding.offlineclient.util;

import net.minecraft.core.Direction;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HayBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.SpeleothemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;

// How the block a fall ends on charges it. The server hands the fall to that block and most
// charge the drop past the safe distance. A bed or a shelf mushroom counts only part of the
// drop. Hay and honey charge a fifth of the damage and slime and powder snow charge nothing
// whether you sneak or not. An upward dripstone tip adds to the drop and charges it twice.
public record Landing(double dropKept, double dropAdded, float scale) {

    private static final Landing PLAIN = new Landing(1, 0, 1);
    private static final Landing NONE = new Landing(1, 0, 0);
    private static final Landing FIFTH = new Landing(1, 0, 0.2f);
    private static final Landing TIP = new Landing(1, 2.5, 2);

    // The server adds this to a fall before it takes the safe distance off.
    private static final double FALL_SLACK = 1.0E-6;

    public static Landing on(BlockState ground) {
        Block block = ground.getBlock();
        return switch (block) {
            case HayBlock _, HoneyBlock _ -> FIFTH;
            case SlimeBlock _, PowderSnowBlock _ -> NONE;
            case PointedDripstoneBlock _ when upwardTip(ground) -> TIP;
            default -> block.getFallDistanceReduction() == 0 ? PLAIN
                : new Landing(1 - block.getFallDistanceReduction(), 0, 1);
        };
    }

    // True when the block charges a fall the way plain ground does.
    public boolean plain() {
        return equals(PLAIN);
    }

    // True when the block charges no fall at all.
    public boolean spares() {
        return scale == 0;
    }

    // The damage a drop comes to on this block before armour and protection. Whole points as
    // the server deals them.
    public float damage(LivingEntity entity, double drop) {
        if (spares() || EntityUtil.typeIs(entity, EntityTypeTags.FALL_DAMAGE_IMMUNE)) {
            return 0;
        }
        double past = drop * dropKept + dropAdded + FALL_SLACK - entity.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
        return Math.max(0, Mth.floor(past * scale * entity.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER)));
    }

    private static boolean upwardTip(BlockState ground) {
        return ground.getValue(SpeleothemBlock.TIP_DIRECTION) == Direction.UP
            && ground.getValue(SpeleothemBlock.THICKNESS) == SpeleothemThickness.TIP;
    }
}
