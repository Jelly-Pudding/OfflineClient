package com.jellypudding.offlineclient.hud;

import com.jellypudding.offlineclient.setting.EnumSetting;

// Which way an element lays out a list of things it shows.
public enum HudLayout {
    ACROSS, DOWN;

    public static EnumSetting<HudLayout> setting(String name, String description) {
        return new EnumSetting<>(name, description, ACROSS)
            .describe(ACROSS, "In a row.")
            .describe(DOWN, "In a column.");
    }
}
