package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

// ScreenMixin skips the dark tint behind a screen.
public final class NoBackground extends Module {

    private final BoolSetting allScreens = new BoolSetting("All screens",
        "Clears the tint behind every screen rather than just inventories.", false);

    public NoBackground() {
        super("NoBackground", "Removes the dark tint behind inventory screens.", Category.RENDER);
        addSettings(allScreens);
        searchTags("gui background", "no gradient");
    }

    // Menus outside a world keep their background. There is nothing to see behind them.
    public boolean clears(Screen screen) {
        if (!isEnabled() || mc.level == null) {
            return false;
        }
        return allScreens.isOn() || screen instanceof AbstractContainerScreen;
    }
}
