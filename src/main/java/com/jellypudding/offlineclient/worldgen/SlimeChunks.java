package com.jellypudding.offlineclient.worldgen;

import net.minecraft.world.level.levelgen.WorldgenRandom;

// The chunks slimes spawn in below height 40 whatever the biome. The game picks them from the
// world seed and the chunk alone.
public final class SlimeChunks {

    // Slime.checkSlimeSpawnRules mixes this into the seed.
    private static final long SALT = 987234911L;
    // One chunk in this many is a slime chunk.
    private static final int ONE_IN = 10;

    private SlimeChunks() {
    }

    public static boolean isSlimeChunk(long seed, int chunkX, int chunkZ) {
        return WorldgenRandom.seedSlimeChunk(chunkX, chunkZ, seed, SALT).nextInt(ONE_IN) == 0;
    }
}
