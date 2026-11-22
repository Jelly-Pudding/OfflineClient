package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.util.ColorUtil;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ChunkPos;

// A colour's alpha says how strongly it covers. Game thread only.
public final class ChunkTints {

    private static final float COVER = 0.5f;

    private Long2IntOpenHashMap colours = new Long2IntOpenHashMap();
    private int revision;
    // The chunk the last look landed in. A picture looks up each chunk many times in a row.
    private long lastChunk = ChunkPos.INVALID_CHUNK_POS;
    private int lastTint;

    // Goes up whenever the colours change. A picture is painted again only when this moves.
    public int revision() {
        return revision;
    }

    public boolean isEmpty() {
        return colours.isEmpty();
    }

    public void set(Long2IntOpenHashMap fresh) {
        if (!fresh.equals(colours)) {
            colours = fresh;
            revision++;
            lastChunk = ChunkPos.INVALID_CHUNK_POS;
        }
    }

    int over(int land, int chunkX, int chunkZ) {
        long chunk = ChunkPos.pack(chunkX, chunkZ);
        if (chunk != lastChunk) {
            lastChunk = chunk;
            lastTint = colours.get(chunk);
        }
        return lastTint == 0 ? land : ARGB.alphaBlend(land, ColorUtil.fade(lastTint, COVER));
    }
}
