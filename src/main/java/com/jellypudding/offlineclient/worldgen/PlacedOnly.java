package com.jellypudding.offlineclient.worldgen;

import net.minecraft.world.level.block.Block;

import java.util.Set;

// Blocks the world never puts down by itself. One of these near you was placed by a player. They
// are every block BaseBlocks lists with the proof for each of them.
public final class PlacedOnly {

    private static final Set<Block> BLOCKS = Set.copyOf(BaseBlocks.NEEDED.keySet());

    private PlacedOnly() {
    }

    public static boolean contains(Block block) {
        return BLOCKS.contains(block);
    }
}
