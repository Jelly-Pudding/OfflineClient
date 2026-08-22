package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.setting.ColorSetting;

/**
 * Shared colors and metrics for the ClickGUI and HUD.
 */
public final class GuiTheme {

    public static final int PANEL_WIDTH = 112;
    public static final int HEADER_HEIGHT = 18;
    public static final int ROW_HEIGHT = 14;
    public static final int SETTING_HEIGHT = 13;

    public static final int BG_PANEL = 0xF014141C;
    public static final int BG_ROW = 0xF01A1A24;
    public static final int BG_ROW_HOVER = 0xF0242432;
    public static final int BG_SETTING = 0xF010101A;
    public static final int TEXT = 0xFFE8E8F0;
    public static final int TEXT_DIM = 0xFF9090A0;
    public static final int OUTLINE = 0xFF060608;

    private GuiTheme() {
    }

    public static ColorSetting accentSetting() {
        return OfflineClient.INSTANCE.getModuleManager().get(ClickGuiModule.class).getAccent();
    }

    public static int accent() {
        return accentSetting().getColor();
    }

    public static int accent(int offset) {
        return accentSetting().getColor(offset);
    }
}
