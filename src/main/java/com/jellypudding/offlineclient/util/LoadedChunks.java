package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
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

    // True whilst the client holds the chunk column round the block at any height. The level's
    // own isLoaded is false above the build height and its hasChunk is true for every chunk.
    public static boolean has(double x, double z) {
        return hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
    }

    public static boolean hasChunk(int chunkX, int chunkZ) {
        return OfflineClient.MC.level.getChunkSource().hasChunk(chunkX, chunkZ);
    }

    public static void forEach(Consumer<LevelChunk> action) {
        Minecraft mc = OfflineClient.MC;
        if (mc.level == null || mc.player == null) {
            return;
        }
        int radius = Modules.renderDistance() + OUTER_RING;
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
