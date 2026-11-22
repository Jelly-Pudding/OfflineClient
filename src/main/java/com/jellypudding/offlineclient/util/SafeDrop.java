package com.jellypudding.offlineclient.util;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class SafeDrop {

    private static final double GROUND_PROBE = 0.1;

    private SafeDrop() {
    }

    // Null when the player would not fit there or the drop would hurt or the ground burns.
    public static Vec3 below(Entity player, Vec3 spot) {
        Level level = player.level();
        AABB box = Hop.boxAt(player, spot);
        if (!level.noCollision(player, box)) {
            return null;
        }
        double safe = DamageUtil.safeFall(player);
        Vec3 fall = Entity.collideBoundingBox(player, new Vec3(0, -(safe + 1), 0), box, level, List.of());
        if (-fall.y > safe) {
            return null;
        }
        boolean burns = level.getBlockStates(box.move(fall).expandTowards(0, -GROUND_PROBE, 0))
            .anyMatch(state -> BlockUtil.harmful(state) || state.getFluidState().is(FluidTags.LAVA));
        return burns ? null : spot.add(fall);
    }
}
