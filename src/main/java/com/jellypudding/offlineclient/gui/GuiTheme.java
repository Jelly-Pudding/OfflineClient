package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.ColorUtil;

// Shared colours and metrics for the ClickGUI and HUD.
public final class GuiTheme {

    public static final int PANEL_WIDTH = 112;
    public static final int HEADER_HEIGHT = 18;
    public static final int ROW_HEIGHT = 16;
    public static final int SETTING_HEIGHT = 14;
    public static final int CORNER = 3;
    public static final int PAD = 6;
    public static final int SCROLLBAR = 4;
    // Cap height of the default font.
    public static final int TEXT_HEIGHT = 7;

    public static final int BG_WINDOW = 0xF80D0D14;
    public static final int BG_PANEL = 0xF2131320;
    public static final int BG_HEADER = 0xF41B1B2A;
    public static final int BG_ROW = 0xF01A1A26;
    public static final int BG_ROW_HOVER = 0xF0272736;
    public static final int BG_SETTING = 0xF00E0E17;
    public static final int TEXT = 0xFFECECF4;
    public static final int TEXT_DIM = 0xFFA6A8BA;
    public static final int TEXT_FAINT = 0xFF70738C;
    public static final int OUTLINE = 0xFF07070C;
    public static final int EDGE = 0xFF2A2C3E;
    public static final int GREEN = 0xFF3FD07E;
    // Hairline between rows in a list.
    public static final int RULE = 0x40000000;
    public static final int SCROLL_TRACK = 0x50000000;
    public static final int SCROLL_THUMB = 0xFF454862;

    private GuiTheme() {
    }

    public static int textY(int rowY, int rowHeight) {
        return rowY + (rowHeight - TEXT_HEIGHT) / 2;
    }

    private static ColorSetting accentSetting;

    public static ColorSetting accentSetting() {
        if (accentSetting == null) {
            accentSetting = OfflineClient.INSTANCE.getModuleManager()
                .get(ClickGuiModule.class).getAccent();
        }
        return accentSetting;
    }

    public static int accent() {
        return accentSetting().getColor();
    }

    public static int accent(int offset) {
        return accentSetting().getColor(offset);
    }

    /**
     * The accent lifted towards white. A saturated blue or red accent is too
     * dark to read on the panel background.
     */
    public static int accentText() {
        return ColorUtil.lerp(accent(), 0xFFFFFFFF, 0.3f);
    }

    public static int accentOn(int background, float amount) {
        return ColorUtil.lerp(background, accent(), amount);
    }

    public static int contrastText(int background) {
        return ColorUtil.luminance(background) > 0.55f ? 0xFF10121A : TEXT;
    }
}
