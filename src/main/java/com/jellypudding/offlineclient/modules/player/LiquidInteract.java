package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;

// Puts liquid surfaces back in the crosshair.
public final class LiquidInteract extends Module {

    private final BoolSetting flowing = new BoolSetting("Flowing",
        "Also stop on flowing liquid and not just on source blocks.", false);

    public LiquidInteract() {
        super("LiquidInteract", "Lets you aim at water and lava instead of looking through it.",
            Category.PLAYER);
        addSettings(flowing);
        searchTags("water", "lava", "place on liquid");
    }

    public boolean includesFlowing() {
        return flowing.isOn();
    }
}
