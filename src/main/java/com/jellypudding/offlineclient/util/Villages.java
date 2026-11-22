package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.worldgen.StructureBeds;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.function.Predicate;

// Looks for a village round a spot on the game thread. Every village piece lies within reach of
// the bell at its centre.
public final class Villages {

    private Villages() {
    }

    public static boolean arrived(BlockPos pos) {
        return ChunkWindow.capture(pos, StructureBeds.VILLAGE_REACH_CHUNKS).complete();
    }

    public static boolean around(BlockPos pos, Predicate<BlockState> marker) {
        ClientLevel level = OfflineClient.MC.level;
        int reach = StructureBeds.VILLAGE_REACH_CHUNKS;
        int centreX = SectionPos.blockToSectionCoord(pos.getX());
        int centreZ = SectionPos.blockToSectionCoord(pos.getZ());
        for (Entity entity : level.entitiesForRendering()) {
            ChunkPos at = entity.chunkPosition();
            if (StructureBeds.villager(entity.getType())
                && at.getChessboardDistance(centreX, centreZ) <= reach) {
                return true;
            }
        }
        for (int x = centreX - reach; x <= centreX + reach; x++) {
            for (int z = centreZ - reach; z <= centreZ + reach; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null && holds(chunk, marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    // The palette is only a first look. It can keep a block that has since gone.
    private static boolean holds(LevelChunk chunk, Predicate<BlockState> wanted) {
        for (LevelChunkSection section : chunk.getSections()) {
            if (section.hasOnlyAir() || !section.maybeHas(wanted)) {
                continue;
            }
            boolean[] found = {false};
            section.getStates().count((state, count) -> found[0] |= wanted.test(state));
            if (found[0]) {
                return true;
            }
        }
        return false;
    }
}
