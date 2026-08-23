package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

// Works out where a hostile mob could appear.
public final class SpawnUtil {

    private static final Minecraft MC = OfflineClient.MC;

    private SpawnUtil() {
    }

    /**
     * The floor has to be a sturdy top face with the two blocks above it
     * clear and dark enough.
     */
    public static boolean spawnable(BlockPos pos, int maxLight) {
        BlockPos below = pos.below();
        BlockState floor = MC.level.getBlockState(below);
        if (floor.isAir() || !floor.isFaceSturdy(MC.level, below, Direction.UP)) {
            return false;
        }
        if (!isOpen(pos) || !isOpen(pos.above())) {
            return false;
        }
        return MC.level.getBrightness(LightLayer.BLOCK, pos) <= maxLight;
    }

    // Matches the checks the game itself runs before spawning a mob.
    private static boolean isOpen(BlockPos pos) {
        BlockState state = MC.level.getBlockState(pos);
        // A slab or a carpet is not a full block and a mob can stand there.
        return !state.isCollisionShapeFullBlock(MC.level, pos)
            && !state.isSignalSource()
            && state.getFluidState().isEmpty()
            && !state.is(BlockTags.PREVENT_MOB_SPAWNING_INSIDE);
    }

    // The vertical reach is separate. Caves are wide and shallow.
    public static List<BlockPos> spotsAround(int horizontal, int vertical, int maxLight, int limit) {
        List<BlockPos> found = new ArrayList<>();
        BlockPos centre = MC.player.blockPosition();
        // Rings outward from the player. Hitting the limit drops the furthest spots.
        int reach = Math.max(horizontal, vertical);
        for (int ring = 0; ring <= reach && found.size() < limit; ring++) {
            for (int dy = -Math.min(ring, vertical); dy <= Math.min(ring, vertical); dy++) {
                for (int dx = -Math.min(ring, horizontal); dx <= Math.min(ring, horizontal); dx++) {
                    for (int dz = -Math.min(ring, horizontal); dz <= Math.min(ring, horizontal); dz++) {
                        if (Math.max(Math.abs(dy), Math.max(Math.abs(dx), Math.abs(dz))) != ring) {
                            continue;
                        }
                        if (found.size() >= limit) {
                            return found;
                        }
                        BlockPos pos = centre.offset(dx, dy, dz);
                        if (spawnable(pos, maxLight)) {
                            found.add(pos);
                        }
                    }
                }
            }
        }
        return found;
    }
}
