package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;

// The behaviour lives in LocalPlayerMixin which reads these settings.
public final class AntiBlind extends Module {

    private final BoolSetting blindness = new BoolSetting("Blindness",
        "Ignores the blindness effect.", true);
    private final BoolSetting darkness = new BoolSetting("Darkness",
        "Ignores the darkness effect and its pulsing dim.", true);
    private final BoolSetting nausea = new BoolSetting("Nausea",
        "Stops the screen spinning from the nausea effect.", true);
    private final BoolSetting portal = new BoolSetting("Portal spin",
        "Stops the screen spinning whilst you stand in a portal.", true);

    public AntiBlind() {
        super("AntiBlind", "Ignores effects that ruin your view.", Category.RENDER);
        addSettings(blindness, darkness, nausea, portal);
        searchTags("blindness", "darkness", "nausea", "no nausea", "portal");
    }

    public boolean blocksBlindness() {
        return blindness.isOn();
    }

    public boolean blocksDarkness() {
        return darkness.isOn();
    }

    public boolean blocksNausea() {
        return nausea.isOn();
    }

    public boolean blocksPortal() {
        return portal.isOn();
    }
}
