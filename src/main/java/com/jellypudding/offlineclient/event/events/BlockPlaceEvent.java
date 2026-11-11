package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.UncancellableEvent;
import net.minecraft.core.BlockPos;

// Fired when a block you place goes down on your screen. A right click by hand and a placement
// by a module both count.
public final class BlockPlaceEvent extends UncancellableEvent {

    private final BlockPos pos;

    public BlockPlaceEvent(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos getPos() {
        return pos;
    }
}
