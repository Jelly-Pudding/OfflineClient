package com.jellypudding.offlineclient.worldgen;

import net.minecraft.world.level.block.state.BlockState;

// The finished world as the client holds it read one block at a time. A chunk window or a
// scanned chunk hands out its get method as one.
@FunctionalInterface
public interface SeenBlocks {

    // A block at a spot of the world.
    @FunctionalInterface
    interface Spot {
        boolean test(int x, int y, int z, BlockState state);
    }

    BlockState at(int x, int y, int z);

    // True when a block the test accepts lies in the cube round a spot.
    static boolean near(SeenBlocks world, int x, int y, int z, int reach, Spot wanted) {
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (wanted.test(x + dx, y + dy, z + dz, world.at(x + dx, y + dy, z + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
