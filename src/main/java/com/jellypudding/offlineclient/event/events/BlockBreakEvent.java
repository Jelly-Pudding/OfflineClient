package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;
import net.minecraft.core.BlockPos;

// Fired every tick whilst the player is mining a block.
public final class BlockBreakEvent extends Event {

    private final BlockPos pos;

    public BlockBreakEvent(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos getPos() {
        return pos;
    }
}
