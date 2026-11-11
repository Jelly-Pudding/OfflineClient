package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.level.LightLayer;

import java.util.ArrayList;
import java.util.List;

// Works out where a hostile mob could appear.
public final class SpawnUtil {

    private static final Minecraft MC = OfflineClient.MC;

    private SpawnUtil() {
    }

    // Sky light at or above this leaves a spot too bright for a mob until dusk.
    private static final int NIGHT_ONLY_SKY_LIGHT = 8;

    // The game's own ground rule with a creeper as the mob. The floor block has to take a spawn
    // and the spot and the block above it have to be open and safe. A creeper's box has to fit
    // when asked and the block light has to be low enough.
    public static boolean spawnable(BlockPos pos, int maxLight, boolean hitbox) {
        if (!SpawnPlacementTypes.ON_GROUND.isSpawnPositionOk(MC.level, pos, EntityTypes.CREEPER)) {
            return false;
        }
        if (hitbox && !MC.level.noCollision(EntityTypes.CREEPER
            .getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5))) {
            return false;
        }
        return MC.level.getBrightness(LightLayer.BLOCK, pos) <= maxLight;
    }

    // True for a spot the sun keeps clear. Mobs appear there once night falls.
    public static boolean nightOnly(BlockPos pos) {
        return MC.level.getBrightness(LightLayer.SKY, pos) >= NIGHT_ONLY_SKY_LIGHT;
    }

    // The vertical reach is separate. Caves are wide and shallow.
    public static List<BlockPos> spotsAround(int horizontal, int vertical, int maxLight, int limit,
                                             boolean hitbox) {
        List<BlockPos> found = new ArrayList<>();
        BlockPos centre = MC.player.blockPosition();
        // Rings outward from the player. Hitting the limit drops the furthest spots.
        int reach = Math.max(horizontal, vertical);
        for (int ring = 0; ring <= reach; ring++) {
            int dyMax = Math.min(ring, vertical);
            int dxMax = Math.min(ring, horizontal);
            int dzMax = Math.min(ring, horizontal);
            for (int dy = -dyMax; dy <= dyMax; dy++) {
                for (int dx = -dxMax; dx <= dxMax; dx++) {
                    if (Math.abs(dy) == ring || Math.abs(dx) == ring) {
                        for (int dz = -dzMax; dz <= dzMax; dz++) {
                            if (!collect(found, centre.offset(dx, dy, dz), maxLight, limit, hitbox)) {
                                return found;
                            }
                        }
                    } else if (dzMax == ring) {
                        // A row through the middle of the shell has only two ends on it.
                        if (!collect(found, centre.offset(dx, dy, -ring), maxLight, limit, hitbox)
                            || !collect(found, centre.offset(dx, dy, ring), maxLight, limit, hitbox)) {
                            return found;
                        }
                    }
                }
            }
        }
        return found;
    }

    // False once the list is full.
    private static boolean collect(List<BlockPos> found, BlockPos pos, int maxLight, int limit,
                                   boolean hitbox) {
        if (found.size() >= limit) {
            return false;
        }
        if (spawnable(pos, maxLight, hitbox)) {
            found.add(pos);
        }
        return true;
    }
}
