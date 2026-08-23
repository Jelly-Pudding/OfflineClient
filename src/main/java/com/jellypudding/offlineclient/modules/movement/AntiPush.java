package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;

// Behaviour lives in LocalPlayerMixin.
public final class AntiPush extends Module {

    private final BoolSetting entities = new BoolSetting("Entities",
        "Players and mobs cannot shove you whilst you still push them.", true);
    private final BoolSetting currents = new BoolSetting("Water currents",
        "Flowing water and lava cannot drag you.", true);
    private final BoolSetting geysers = new BoolSetting("Geysers",
        "Bubble columns cannot lift or pull you.", true);

    public AntiPush() {
        super("AntiPush", "Stops water and entities from pushing you around.", Category.MOVEMENT);
        addSettings(entities, currents, geysers);
        searchTags("anti water push", "anti entity push", "bubble", "collision", "shove");
    }

    public boolean blocksEntities() {
        return isEnabled() && entities.isOn();
    }

    public boolean blocksCurrents() {
        return isEnabled() && currents.isOn();
    }

    public boolean blocksBubbleColumns() {
        return isEnabled() && geysers.isOn();
    }
}
