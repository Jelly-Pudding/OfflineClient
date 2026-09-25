package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// Shared colours and metrics for the ClickGUI and HUD.
public final class GuiTheme {

    public static final int PANEL_WIDTH = 112;
    // How tall a panel grows before it scrolls. The bottom edge drags to change it.
    public static final int PANEL_VIEW_HEIGHT = 190;
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
    private static final int DEFAULT_TOOLTIP = 0xF00E0E14;
    private static final int DEFAULT_TEXT = 0xFFECECF4;
    private static final int DEFAULT_TEXT_DIM = 0xFFA6A8BA;
    private static final int DEFAULT_TEXT_FAINT = 0xFF70738C;
    private static final int DEFAULT_OUTLINE = 0xFF07070C;
    private static final int DEFAULT_EDGE = 0xFF2A2C3E;
    private static final int DEFAULT_THUMB = 0xFF454862;

    public static final int GREEN = 0xFF3FD07E;
    // Anything that throws work away. The lighter one is for writing.
    public static final int RED = 0xFFE05050;
    public static final int RED_TEXT = 0xFFFF9090;
    // Hairline between rows in a list.
    public static final int RULE = 0x40000000;

    public static final int STAR = 0xFFF2C744;
    // A multiplication sign. The closest thing to a cross the font has.
    public static final String CROSS = "×";
    // How far outside an edge a press still grabs it for a resize.
    public static final int GRAB = 4;
    // A faint light laid over a row the pointer is on.
    public static final int HOVER_WASH = 0x14FFFFFF;
    // HUD elements draw in the untinted text colour over a dark bar track.
    public static final int HUD_TEXT = DEFAULT_TEXT;
    public static final int BAR_TRACK = 0xC0202020;
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

    // The panel shade with the opacity left out. Used where a surface has
    // to hide whatever it covers.
    public static int bgSolid() {
        return solid(DEFAULT_PANEL);
    }

    public static int bgTooltip() {
        return solid(DEFAULT_TOOLTIP);
    }

    private static int solid(int shade) {
        ClickGuiModule gui = gui();
        return gui == null ? shade
            : ColorUtil.withAlpha(ColorUtil.retint(shade, DEFAULT_PANEL, gui.background()), 255);
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

    // The tint keeps the background's own alpha. An accent is solid and would
    // otherwise make a tinted row heavier than the panel it sits in.
    public static int accentOn(int background, float amount) {
        return ColorUtil.withAlpha(ColorUtil.lerp(background, accent(), amount), background >>> 24);
    }

    public static int contrastText(int background) {
        return ColorUtil.luminance(background) > 0.55f ? 0xFF10121A : text();
    }

    // A rounded button with its label in the middle. A button that is not live greys out.
    public static void button(GuiGraphicsExtractor context, Font font, int x, int y, int w, int h,
                              String label, boolean hovered, boolean live) {
        RenderUtil.roundedBorderedRect(context, x, y, x + w, y + h, CORNER,
            hovered ? bgRowHover() : bgPanel(), hovered ? accent() : edge());
        context.guiRenderState.up();
        context.centeredText(font, label, x + w / 2, textY(y, h),
            live ? (hovered ? accentText() : text()) : textFaint());
    }
}
