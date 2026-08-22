package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;

/**
 * The behavior lives in LevelMixin which checks isEnabled.
 */
public final class ClearSkies extends Module {

    public ClearSkies() {
        super("ClearSkies", "Hides rain and thunder on your screen.", Category.RENDER);
    }
}
