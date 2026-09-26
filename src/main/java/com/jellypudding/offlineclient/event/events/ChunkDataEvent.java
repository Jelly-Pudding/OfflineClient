package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.UncancellableEvent;
import net.minecraft.world.level.chunk.LevelChunk;

// Fired on the game thread the moment a chunk the server sent is written into the world.
// Nothing has changed it since. Its palettes still list their entries in the server's order.
public final class ChunkDataEvent extends UncancellableEvent {

    private final LevelChunk chunk;

    public ChunkDataEvent(LevelChunk chunk) {
        this.chunk = chunk;
    }

    public LevelChunk getChunk() {
        return chunk;
    }
}
