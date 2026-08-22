package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;

/**
 * The behavior lives in LocalPlayerMixin which checks isEnabled.
 */
public final class NoSlowdown extends Module {

    public NoSlowdown() {
        super("NoSlowdown", "Move at full speed while eating or blocking.",
            Category.MOVEMENT);
    }
}
