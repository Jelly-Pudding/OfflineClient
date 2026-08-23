package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;

/**
 * Lifts the three vanilla checks that stop one action whilst another is
 * running. The work is done in MinecraftMixin which reads these settings.
 */
public final class Multitask extends Module {

    private final BoolSetting whileMining = new BoolSetting("Use whilst mining",
        "Place and use items whilst you are breaking a block.", true);
    private final BoolSetting whileUsing = new BoolSetting("Mine whilst using",
        "Break blocks whilst you are eating or drawing a bow.", true);
    private final BoolSetting attackWhileUsing = new BoolSetting("Attack whilst using",
        "Hit entities whilst you are eating or drawing a bow.", true);

    public Multitask() {
        super("Multitask", "Mine and place blocks whilst you eat or draw a bow.",
            Category.PLAYER);
        addSettings(whileMining, whileUsing, attackWhileUsing);
        searchTags("multi task", "eat and mine", "bow mine");
    }

    public boolean usesWhileMining() {
        return isEnabled() && whileMining.isOn();
    }

    public boolean minesWhileUsing() {
        return isEnabled() && whileUsing.isOn();
    }

    public boolean attacksWhileUsing() {
        return isEnabled() && attackWhileUsing.isOn();
    }
}
