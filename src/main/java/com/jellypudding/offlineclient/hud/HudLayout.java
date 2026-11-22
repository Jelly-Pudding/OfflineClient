package com.jellypudding.offlineclient.hud;

import com.jellypudding.offlineclient.setting.EnumSetting;

public enum HudLayout {
    ACROSS, DOWN;

    public static EnumSetting<HudLayout> setting(String name, String description) {
        return new EnumSetting<>(name, description, ACROSS)
            .describe(ACROSS, "In a row.")
            .describe(DOWN, "In a column.");
    }
}
