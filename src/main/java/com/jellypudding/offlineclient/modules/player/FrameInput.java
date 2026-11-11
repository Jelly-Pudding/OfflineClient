package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;

// MinecraftMixin reads the keys after every drawn frame whilst this is on and the tick skips
// its own read. Mining still goes once a tick.
public final class FrameInput extends Module {

    public FrameInput() {
        super("FrameInput", "Reads your keys and mining every drawn frame rather than every tick.",
            Category.PLAYER);
        searchTags("frame input", "input lag", "keys every frame");
    }
}
