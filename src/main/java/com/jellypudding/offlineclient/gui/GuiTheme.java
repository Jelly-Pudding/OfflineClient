package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.modules.render.Blur;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

public final class GuiTheme {

    public static final int PANEL_WIDTH = 112;
    public static final int PANEL_VIEW_HEIGHT = 190;
    public static final int HEADER_HEIGHT = 18;
    public static final int ROW_HEIGHT = 16;
    public static final int SETTING_HEIGHT = 14;
    public static final int CORNER = 3;
    public static final int PAD = 6;
    public static final int SCROLLBAR = 4;
    public static final int SCROLL_GUTTER = SCROLLBAR + 2;
    // Cap height of the default font.
    public static final int TEXT_HEIGHT = 7;

    // The background and text settings move the whole family onto a colour of your own whilst
    // keeping these relative weights.
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
    public static final int RED = 0xFFE05050;
    public static final int RED_TEXT = 0xFFFF9090;
    public static final int RULE = 0x40000000;

    public static final int STAR = 0xFFF2C744;
    // A multiplication sign. The closest thing to a cross the font has.
    public static final String CROSS = "×";
    public static final int GRAB = 4;
    public static final int HOVER_WASH = 0x14FFFFFF;
    public static final int HUD_TEXT = DEFAULT_TEXT;
    public static final int BAR_TRACK = 0xC0202020;
    public static final int SCROLL_TRACK = 0x50000000;
    private static final float LIGHT_BACKGROUND = 0.55f;
    private static final int DARK_TEXT = 0xFF10121A;
    private static final int DIM_TOP = 0x70101018;
    private static final int DIM_BOTTOM = 0xA0060610;

    private GuiTheme() {
    }

    private static ClickGuiModule gui() {
        return Modules.get(ClickGuiModule.class);
    }

    private static int tinted(int shade) {
        return ColorUtil.retint(shade, DEFAULT_PANEL, gui().background());
    }

    private static int bg(int shade) {
        return ColorUtil.fade(tinted(shade), gui().opacity());
    }

    private static int ink(int shade) {
        return ColorUtil.retint(shade, DEFAULT_TEXT, gui().textColor());
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

    // Used where a surface has to hide whatever it covers.
    public static int bgSolid() {
        return solid(DEFAULT_PANEL);
    }

    public static int bgTooltip() {
        return solid(DEFAULT_TOOLTIP);
    }

    private static int solid(int shade) {
        return ColorUtil.withAlpha(tinted(shade), 255);
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

    // A see through panel still has a border.
    public static int outline() {
        return tinted(DEFAULT_OUTLINE);
    }

    public static int edge() {
        return tinted(DEFAULT_EDGE);
    }

    public static int scrollThumb() {
        return tinted(DEFAULT_THUMB);
    }

    public static int textY(int rowY, int rowHeight) {
        return rowY + (rowHeight - TEXT_HEIGHT) / 2;
    }

    private static ColorSetting accentSetting() {
        return gui().getAccent();
    }

    public static int accent() {
        return accentSetting().getColor();
    }

    public static int accent(int offset) {
        return accentSetting().getColor(offset);
    }

    // A saturated blue or red accent is too dark to read on the panel background.
    public static int accentText() {
        return ColorUtil.lerp(accent(), 0xFFFFFFFF, 0.3f);
    }

    // The tint keeps the background's own alpha. An accent is solid and would
    // otherwise make a tinted row heavier than the panel it sits in.
    public static int accentOn(int background, float amount) {
        return ColorUtil.withAlpha(ColorUtil.lerp(background, accent(), amount), background >>> 24);
    }

    public static int contrastText(int background) {
        return contrastText(background, text());
    }

    public static int contrastText(int background, int light) {
        return ColorUtil.luminance(background) > LIGHT_BACKGROUND ? DARK_TEXT : light;
    }

    static boolean hoverHelp() {
        return gui().showsHoverHelp();
    }

    // The vanilla blur washes the panel colours out. Blur adds its own when
    // asked. The wash over the world follows the opacity setting because a
    // see through panel is worth nothing over a blacked out world.
    static void dimBackground(Screen screen, GuiGraphicsExtractor context) {
        Blur blur = Modules.get(Blur.class);
        if (blur != null && blur.wants(screen)) {
            blur.blurHere(context);
        }
        float share = gui().opacity();
        context.fillGradient(0, 0, screen.width, screen.height,
            ColorUtil.fade(DIM_TOP, share), ColorUtil.fade(DIM_BOTTOM, share));
    }

    public static void button(GuiGraphicsExtractor context, Font font, int x, int y, int w, int h,
                              String label, boolean hovered, boolean live) {
        RenderUtil.roundedBorderedRect(context, x, y, x + w, y + h, CORNER,
            hovered ? bgRowHover() : bgPanel(), hovered ? accent() : edge());
        context.guiRenderState.up();
        context.centeredText(font, label, x + w / 2, textY(y, h),
            live ? (hovered ? accentText() : text()) : textFaint());
    }
}
