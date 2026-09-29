package com.jellypudding.offlineclient.worldgen;

import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Set;

// Blocks the world never puts down by itself. One of these near you was placed by a player. They
// are every block of the BaseBlocks groups where the proof for each of them lives.
public final class PlacedOnly {

    public static final List<Block> BLOCKS = BaseBlocks.GROUPS.stream().flatMap(List::stream).distinct().toList();

    private static final Set<Block> SET = Set.copyOf(BLOCKS);

    private PlacedOnly() {
    }

    public static boolean contains(Block block) {
        return SET.contains(block);
    }
}
