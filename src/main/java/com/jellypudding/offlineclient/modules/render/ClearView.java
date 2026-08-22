package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;

/**
 * Overlay removal happens in HudMixin which checks these settings.
 */
public final class ClearView extends Module {

    private final BoolSetting pumpkin = new BoolSetting("Pumpkin",
        "Removes the carved pumpkin overlay.", true);
    private final BoolSetting powderSnow = new BoolSetting("Powder snow",
        "Removes the powder snow frost overlay.", true);
    private final BoolSetting vignette = new BoolSetting("Vignette",
        "Removes the dark border around the screen edges.", false);

    public ClearView() {
        super("ClearView", "Removes annoying screen overlays.", Category.RENDER);
        addSettings(pumpkin, powderSnow, vignette);
    }

    public boolean blocksPumpkin() {
        return pumpkin.isOn();
    }

    public boolean blocksPowderSnow() {
        return powderSnow.isOn();
    }

    public boolean blocksVignette() {
        return vignette.isOn();
    }
}
