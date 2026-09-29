package com.jellypudding.offlineclient.worldgen;

import net.minecraft.world.level.block.state.BlockState;

// The finished world as the client holds it read one block at a time. A chunk window or a
// scanned chunk hands out its get method as one.
@FunctionalInterface
public interface SeenBlocks {

    BlockState at(int x, int y, int z);
}
