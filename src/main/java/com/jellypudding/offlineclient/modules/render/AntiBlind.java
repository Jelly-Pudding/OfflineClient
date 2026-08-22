package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;

/**
 * The behavior lives in LocalPlayerMixin which checks isEnabled.
 */
public final class AntiBlind extends Module {

    public AntiBlind() {
        super("AntiBlind", "Ignores blindness and darkness effects.", Category.RENDER);
    }
}
