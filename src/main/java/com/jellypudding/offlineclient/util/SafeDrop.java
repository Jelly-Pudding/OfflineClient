package com.jellypudding.offlineclient.util;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Where a player set down at a spot comes to rest on the floor beneath it.
public final class SafeDrop {

    // How far under the landing a block that burns or pricks still counts.
    private static final double GROUND_PROBE = 0.1;

    private SafeDrop() {
    }

    // The spot on the floor under the given one. Null when the player would not fit there or
    // the drop would hurt or the ground burns.
    public static Vec3 below(Entity player, Vec3 spot) {
        Level level = player.level();
        AABB box = Hop.boxAt(player, spot);
        if (!level.noCollision(player, box)) {
            return null;
        }
        Vec3 fall = Entity.collideBoundingBox(player, new Vec3(0, -(DamageUtil.SAFE_FALL + 1), 0), box,
            level, List.of());
        if (-fall.y > DamageUtil.SAFE_FALL) {
            return null;
        }
        boolean burns = level.getBlockStates(box.move(fall).expandTowards(0, -GROUND_PROBE, 0))
            .anyMatch(state -> BlockUtil.harmful(state) || state.getFluidState().is(FluidTags.LAVA));
        return burns ? null : spot.add(fall);
    }
}
