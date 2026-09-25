package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

// A read only window on the world. A captured window holds its chunks and a worker
// thread can read it without touching the live chunk map. Reads outside it come
// back as void air.
public final class ChunkWindow {

    private static final BlockState OUTSIDE = Blocks.VOID_AIR.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final ClientChunkCache source;
    private final LevelChunk[] chunks;
    private final int originX;
    private final int originZ;
    private final int side;
    private final int minY;
    private final int maxY;

    private ChunkWindow(ClientChunkCache source, LevelChunk[] chunks,
                        int originX, int originZ, int side, int minY, int maxY) {
        this.source = source;
        this.chunks = chunks;
        this.originX = originX;
        this.originZ = originZ;
        this.side = side;
        this.minY = minY;
        this.maxY = maxY;
    }

    // Reads straight from the world. Only safe on the client thread.
    public static ChunkWindow live() {
        ClientLevel level = OfflineClient.MC.level;
        return new ChunkWindow(level.getChunkSource(), null, 0, 0, 0, level.getMinY(), level.getMaxY());
    }

    public static ChunkWindow capture(BlockPos centre, int radius) {
        return capture(centre.getX() >> 4, centre.getZ() >> 4, radius);
    }

    // The chunk at the given chunk coordinates and every chunk within the radius of it.
    public static ChunkWindow capture(int chunkX, int chunkZ, int radius) {
        ClientLevel level = OfflineClient.MC.level;
        int side = radius * 2 + 1;
        int originX = chunkX - radius;
        int originZ = chunkZ - radius;
        LevelChunk[] chunks = new LevelChunk[side * side];
        for (int dx = 0; dx < side; dx++) {
            for (int dz = 0; dz < side; dz++) {
                chunks[dx * side + dz] = level.getChunkSource()
                    .getChunk(originX + dx, originZ + dz, ChunkStatus.FULL, false);
            }
        }
        return new ChunkWindow(null, chunks, originX, originZ, side, level.getMinY(), level.getMaxY());
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public BlockState get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockState get(int x, int y, int z) {
        if (y < minY || y > maxY) {
            return OUTSIDE;
        }
        LevelChunk chunk = chunkAt(x >> 4, z >> 4);
        if (chunk == null) {
            return OUTSIDE;
        }
        LevelChunkSection section = chunk.getSections()[chunk.getSectionIndex(y)];
        if (section == null || section.hasOnlyAir()) {
            return AIR;
        }
        return section.getBlockState(x & 15, y & 15, z & 15);
    }

    private LevelChunk chunkAt(int x, int z) {
        if (source != null) {
            return source.getChunk(x, z, ChunkStatus.FULL, false);
        }
        int dx = x - originX;
        int dz = z - originZ;
        if (dx < 0 || dx >= side || dz < 0 || dz >= side) {
            return null;
        }
        return chunks[dx * side + dz];
    }
}
