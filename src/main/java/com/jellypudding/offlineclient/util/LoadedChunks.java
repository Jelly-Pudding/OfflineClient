package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.function.Consumer;

// The chunks the client holds around the player. The server sends a ring just past the
// view distance as well. Game thread only.
public final class LoadedChunks {

    private static final int OUTER_RING = 1;

    private LoadedChunks() {
    }

    public static void forEach(Consumer<LevelChunk> action) {
        Minecraft mc = OfflineClient.MC;
        if (mc.level == null || mc.player == null) {
            return;
        }
        int radius = mc.options.getEffectiveRenderDistance() + OUTER_RING;
        ChunkPos centre = mc.player.chunkPosition();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = mc.level.getChunkSource()
                    .getChunk(centre.x() + dx, centre.z() + dz, ChunkStatus.FULL, false);
                if (chunk != null) {
                    action.accept(chunk);
                }
            }
        }
    }

    public static void forEachBlockEntity(Consumer<BlockEntity> action) {
        forEach(chunk -> chunk.getBlockEntities().values().forEach(action));
    }
}
