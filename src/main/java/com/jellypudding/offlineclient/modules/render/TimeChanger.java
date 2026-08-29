package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;

// ClientClockManagerMixin answers the day clock with this time. The server keeps its own.
public final class TimeChanger extends Module {

    private static final int DAY_LENGTH = 24000;

    private final NumberSetting time = new NumberSetting("Time",
        "Tick of the day to show. Zero is sunrise and eighteen thousand is midnight.",
        6000, 0, DAY_LENGTH, 500, " ticks");

    public TimeChanger() {
        super("TimeChanger", "Shows the world at a time of day you pick.", Category.RENDER);
        addSettings(time);
        searchTags("day", "night", "no weather", "moon");
    }

    @Override
    public String getSuffix() {
        return time.getValueString();
    }

    public long clockTime() {
        return time.getInt() % DAY_LENGTH;
    }
}
