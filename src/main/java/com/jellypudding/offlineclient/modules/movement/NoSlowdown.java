package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;

// Behaviour lives in LocalPlayerMixin.
public final class NoSlowdown extends Module {

    public NoSlowdown() {
        super("NoSlowdown", "Move at full speed whilst eating or blocking.",
            Category.MOVEMENT);
    }
}
