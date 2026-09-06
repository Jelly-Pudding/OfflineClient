package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.level.MoonPhase;

// ClientClockManagerMixin answers the day clock with this time. The server keeps its own.
public final class TimeChanger extends Module {

    private static final int DAY_LENGTH = 24000;

    private final NumberSetting time = new NumberSetting("Time",
        "Tick of the day to show. Zero is sunrise and eighteen thousand is midnight.",
        6000, 0, DAY_LENGTH, 500, " ticks");

    private final BoolSetting changeMoon = new BoolSetting("Change moon phase",
        "Also fixes the moon to a face you pick.", false);
    private final EnumSetting<MoonPhase> moon = new EnumSetting<>("Moon phase",
        "Which face of the moon hangs in your sky.", MoonPhase.FULL_MOON)
        .under(changeMoon);

    public TimeChanger() {
        super("TimeChanger", "Shows the world at a time of day you pick.", Category.RENDER);
        addSettings(time, changeMoon, moon);
        searchTags("day", "night", "time of day", "moon");
    }

    @Override
    public String getSuffix() {
        return time.getValueString();
    }

    // The phase is the day count so a whole day is added per step.
    public long clockTime() {
        long day = time.getInt() % DAY_LENGTH;
        return changeMoon.isOn() ? moon.getValue().startTick() + day : day;
    }
}
