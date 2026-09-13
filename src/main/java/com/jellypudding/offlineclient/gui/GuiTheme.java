package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Modules;

// Shared colours and metrics for the ClickGUI and HUD.
public final class GuiTheme {

    public static final int PANEL_WIDTH = 112;
    public static final int HEADER_HEIGHT = 18;
    public static final int ROW_HEIGHT = 16;
    public static final int SETTING_HEIGHT = 14;
    public static final int CORNER = 3;
    public static final int PAD = 6;
    public static final int SCROLLBAR = 4;
    // Width a scrollbar takes out of the rows beside it.
    public static final int SCROLL_GUTTER = SCROLLBAR + 2;
    // Cap height of the default font.
    public static final int TEXT_HEIGHT = 7;

    // The shades the interface ships with. The background and text settings move the
    // whole family onto a colour of your own whilst keeping these relative weights.
    private static final int DEFAULT_WINDOW = 0xF80D0D14;
    private static final int DEFAULT_PANEL = 0xF2131320;
    private static final int DEFAULT_HEADER = 0xF41B1B2A;
    private static final int DEFAULT_ROW = 0xF01A1A26;
    private static final int DEFAULT_ROW_HOVER = 0xF0272736;
    private static final int DEFAULT_SETTING = 0xF00E0E17;
    private static final int DEFAULT_TEXT = 0xFFECECF4;
    private static final int DEFAULT_TEXT_DIM = 0xFFA6A8BA;
    private static final int DEFAULT_TEXT_FAINT = 0xFF70738C;
    private static final int DEFAULT_OUTLINE = 0xFF07070C;
    private static final int DEFAULT_EDGE = 0xFF2A2C3E;
    private static final int DEFAULT_THUMB = 0xFF454862;

    public static final int GREEN = 0xFF3FD07E;
    // Hairline between rows in a list.
    public static final int RULE = 0x40000000;
    public static final int SCROLL_TRACK = 0x50000000;

    private GuiTheme() {
    }

    // Null before the client has built its modules.
    private static ClickGuiModule gui() {
        return Modules.get(ClickGuiModule.class);
    }

    // One background shade moved onto the chosen colour and thinned by the opacity.
    private static int bg(int shade) {
        ClickGuiModule gui = gui();
        if (gui == null) {
            return shade;
        }
        return ColorUtil.fade(ColorUtil.retint(shade, DEFAULT_PANEL, gui.background()),
            gui.opacity());
    }

    private static int ink(int shade) {
        ClickGuiModule gui = gui();
        return gui == null ? shade : ColorUtil.retint(shade, DEFAULT_TEXT, gui.textColor());
    }

    public static int bgWindow() {
        return bg(DEFAULT_WINDOW);
    }

    public static int bgPanel() {
        return bg(DEFAULT_PANEL);
    }

    public static int bgHeader() {
        return bg(DEFAULT_HEADER);
    }

    public static int bgRow() {
        return bg(DEFAULT_ROW);
    }

    public static int bgRowHover() {
        return bg(DEFAULT_ROW_HOVER);
    }

    public static int bgSetting() {
        return bg(DEFAULT_SETTING);
    }

    public static int text() {
        return ink(DEFAULT_TEXT);
    }

    public static int textDim() {
        return ink(DEFAULT_TEXT_DIM);
    }

    public static int textFaint() {
        return ink(DEFAULT_TEXT_FAINT);
    }

    // The outline and the edge stay solid. A see through panel still has a border.
    public static int outline() {
        ClickGuiModule gui = gui();
        return gui == null ? DEFAULT_OUTLINE
            : ColorUtil.retint(DEFAULT_OUTLINE, DEFAULT_PANEL, gui.background());
    }

    public static int edge() {
        ClickGuiModule gui = gui();
        return gui == null ? DEFAULT_EDGE
            : ColorUtil.retint(DEFAULT_EDGE, DEFAULT_PANEL, gui.background());
    }

    public static int scrollThumb() {
        ClickGuiModule gui = gui();
        return gui == null ? DEFAULT_THUMB
            : ColorUtil.retint(DEFAULT_THUMB, DEFAULT_PANEL, gui.background());
    }

    public static int textY(int rowY, int rowHeight) {
        return rowY + (rowHeight - TEXT_HEIGHT) / 2;
    }

    private static ColorSetting accentSetting() {
        return Modules.get(ClickGuiModule.class).getAccent();
    }

    public static int accent() {
        return accentSetting().getColor();
    }

    public static int accent(int offset) {
        return accentSetting().getColor(offset);
    }

    // The accent lifted towards white. A saturated blue or red accent is too
    // dark to read on the panel background.
    public static int accentText() {
        return ColorUtil.lerp(accent(), 0xFFFFFFFF, 0.3f);
    }

    public static int accentOn(int background, float amount) {
        return ColorUtil.lerp(background, accent(), amount);
    }

    public static int contrastText(int background) {
        return ColorUtil.luminance(background) > 0.55f ? 0xFF10121A : text();
    }
}
