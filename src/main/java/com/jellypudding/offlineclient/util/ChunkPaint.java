package com.jellypudding.offlineclient.util;

import it.unimi.dsi.fastutil.longs.Long2IntMap;

// A module that paints the chunks it marked onto the map. The map asks every one that is on.
public interface ChunkPaint {

    // Puts each marked chunk of the dimension you are in with the colour it is painted. The
    // colour's alpha says how strongly it covers the land.
    void paintChunks(Long2IntMap colours);
}
