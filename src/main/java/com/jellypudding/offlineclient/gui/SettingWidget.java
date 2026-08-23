package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

// Draws one setting row and turns clicks on it into changes.
public final class SettingWidget {

    public static final int PAD = GuiTheme.PAD;

    private static final int VALUE_BAND = GuiTheme.SETTING_HEIGHT - 5;
    public static final int INDENT = 10;
    private static final int BOX = 9;

    private static final String BIND_HELP =
        "Click here and then press a key. DELETE unbinds. ESC cancels.";

    private SettingWidget() {
    }

    public interface Host {

        void setTooltip(String text);

        boolean isEditing(Setting<?> setting);

        TextField getEditField();

        boolean isBinding(KeybindSetting setting);

        void startEditing(NumberSetting setting);

        void startEditing(TextSetting setting);

        void startListening(KeybindSetting setting);

        void openPicker(RegistryListSetting<?> setting);
    }

    // A slider or hue bar keeps following the mouse even when it leaves the row.
    public static final class Drag {

        private NumberSetting slider;
        private ColorSetting hue;

        public boolean isActive() {
            return slider != null || hue != null;
        }

        // Called every frame with the geometry of the settings block.
        public void follow(int x, int width, int mouseX) {
            if (slider != null) {
                slider.setFromSlider(fraction(x, width, mouseX));
            }
            if (hue != null) {
                hue.setHue((float) fraction(x, width, mouseX) * 360f);
            }
        }

        public void release() {
            if (isActive()) {
                OfflineClient.INSTANCE.getConfigManager().saveSoon();
            }
            if (slider != null) {
                slider.endSlider();
            }
            slider = null;
            hue = null;
        }
    }

    public static int visibleCount(Module module) {
        int count = 0;
        List<Setting<?>> settings = module.getSettings();
        for (int i = 0; i < settings.size(); i++) {
            if (settings.get(i).isVisible()) {
                count++;
            }
        }
        return count;
    }

    public static boolean isOver(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static double fraction(int x, int width, double mx) {
        int span = Math.max(1, width - 2 * PAD);
        return Math.clamp((mx - (x + PAD)) / span, 0, 1);
    }

    // The typed value box on a number row. Shared by render and click.
    private static boolean overNumberValue(Font font, double mx, double my, NumberSetting n,
                                           int x, int y, int w) {
        int right = x + w - PAD;
        int left = right - font.width(n.getValueString()) - 3;
        return mx >= left && mx <= right + 1 && my >= y && my < y + VALUE_BAND;
    }

    public static void render(GuiGraphicsExtractor context, Font font, Setting<?> setting,
                              int x, int y, int width, int mouseX, int mouseY,
                              boolean hoverAllowed, Host host) {
        int w = width;
        int h = GuiTheme.SETTING_HEIGHT;
        int ty = GuiTheme.textY(y, h);
        // Rows with a bar at the bottom centre their text in the band above it.
        int bandY = GuiTheme.textY(y, VALUE_BAND);
        boolean hovered = hoverAllowed && isOver(mouseX, mouseY, x, y, w, h);
        if (hovered) {
            host.setTooltip(setting.getDescription());
            context.fill(x, y, x + w, y + h, 0x14FFFFFF);
            context.guiRenderState.up();
        }
        int nameColor = hovered ? GuiTheme.TEXT : GuiTheme.TEXT_DIM;

        switch (setting) {
            case BoolSetting b -> {
                context.text(font, b.getName(), x + PAD, ty, nameColor, false);
                checkbox(context, x + w - PAD - BOX, y + (h - BOX) / 2, b.isOn());
            }
            case NumberSetting n -> {
                context.text(font, n.getName(), x + PAD, bandY, nameColor, false);
                renderNumber(context, font, n, x, y, w, mouseX, mouseY, hoverAllowed, host);
            }
            case KeybindSetting k -> {
                context.text(font, k.getName(), x + PAD, ty, nameColor, false);
                keyChip(context, font, x + w - PAD, y, host.isBinding(k) ? "press a key" : k.getKeyName(),
                    host.isBinding(k));
                if (hovered) {
                    host.setTooltip(BIND_HELP);
                }
            }
            case EnumSetting<?> e -> {
                context.text(font, e.getName(), x + PAD, ty, nameColor, false);
                String value = trimEnd(font, String.valueOf(e.getValue()),
                    w - 2 * PAD - 6 - font.width(e.getName()));
                context.text(font, value, x + w - PAD - font.width(value), ty,
                    GuiTheme.accentText(), false);
            }
            case ColorSetting c -> {
                context.text(font, c.getName(), x + PAD, bandY, nameColor, false);
                renderColor(context, font, c, x, y, w);
            }
            case RegistryListSetting<?> r -> {
                context.text(font, r.getName(), x + PAD, ty, nameColor, false);
                String value = r.size() + " chosen";
                context.text(font, value, x + w - PAD - font.width(value), ty,
                    GuiTheme.accentText(), false);
            }
            case TextSetting t -> renderText(context, font, t, x, y, w, nameColor, ty, host);
            default -> context.text(font, setting.getName(), x + PAD, ty, nameColor, false);
        }
    }

    private static void renderNumber(GuiGraphicsExtractor context, Font font, NumberSetting n,
                                     int x, int y, int w, int mouseX, int mouseY,
                                     boolean hoverAllowed, Host host) {
        boolean editing = host.isEditing(n);
        boolean overValue = hoverAllowed && overNumberValue(font, mouseX, mouseY, n, x, y, w);
        int highlight = editing || overValue ? GuiTheme.accentText() : GuiTheme.TEXT;
        int valueY = GuiTheme.textY(y, VALUE_BAND);
        int valueX;
        if (editing) {
            // A number being typed grows to the left so the caret stays put.
            int room = w - 2 * PAD;
            valueX = x + w - PAD - room;
            host.getEditField().render(context, font, valueX, valueY, room, highlight, true);
        } else {
            String value = n.getValueString();
            valueX = x + w - PAD - font.width(value);
            context.text(font, value, valueX, valueY, highlight, false);
        }
        context.fill(valueX, y + VALUE_BAND - 1, x + w - PAD, y + VALUE_BAND,
            editing || overValue ? GuiTheme.accentText() : GuiTheme.SCROLL_THUMB);

        int barY = y + GuiTheme.SETTING_HEIGHT - 4;
        int barW = w - 2 * PAD;
        RenderUtil.roundedRect(context, x + PAD, barY, x + PAD + barW, barY + 3, 1,
            GuiTheme.BG_ROW_HOVER);
        context.guiRenderState.up();
        int fill = (int) (barW * n.getSliderFraction());
        if (fill > 0) {
            RenderUtil.roundedRect(context, x + PAD, barY, x + PAD + fill, barY + 3, 1,
                GuiTheme.accent());
        }
        context.guiRenderState.up();
        int knob = Math.clamp(x + PAD + fill, x + PAD + 1, x + PAD + barW - 1);
        context.fill(knob - 1, barY - 1, knob + 2, barY + 4, GuiTheme.accentText());
    }

    // The rainbow toggle needs a visible control. Nobody finds a right click.
    private static final String RAINBOW_LABEL = "RGB";

    private static int rainbowChipWidth(Font font) {
        return font.width(RAINBOW_LABEL) + 6;
    }

    private static int rainbowChipX(Font font, int x, int w) {
        return x + w - PAD - BOX - 4 - rainbowChipWidth(font);
    }

    private static boolean overRainbowChip(Font font, double mx, double my, int x, int y, int w) {
        return isOver(mx, my, rainbowChipX(font, x, w), y + 1, rainbowChipWidth(font), BOX);
    }

    private static void renderColor(GuiGraphicsExtractor context, Font font, ColorSetting c,
                                    int x, int y, int w) {
        int chipX = rainbowChipX(font, x, w);
        int chipW = rainbowChipWidth(font);
        RenderUtil.roundedBorderedRect(context, chipX, y + 1, chipX + chipW, y + 1 + BOX, 2,
            c.isRainbow() ? GuiTheme.accentOn(GuiTheme.BG_SETTING, 0.7f) : GuiTheme.BG_ROW,
            c.isRainbow() ? GuiTheme.accent() : GuiTheme.EDGE);
        context.guiRenderState.up();
        context.text(font, RAINBOW_LABEL, chipX + 3, y + 1 + (BOX - GuiTheme.TEXT_HEIGHT) / 2,
            c.isRainbow() ? GuiTheme.TEXT : GuiTheme.TEXT_FAINT, false);

        box(context, x + w - PAD - BOX, y + 1, BOX, c.getColor());
        int barY = y + GuiTheme.SETTING_HEIGHT - 4;
        int barW = w - 2 * PAD;
        RenderUtil.hueBar(context, x + PAD, barY, barW, 3);
        context.guiRenderState.up();
        if (c.isRainbow()) {
            return;
        }
        int cursor = x + PAD + (int) (barW * (c.getHue() / 360f));
        cursor = Math.clamp(cursor, x + PAD, x + PAD + barW - 1);
        context.fill(cursor - 1, barY - 1, cursor + 2, barY + 4, 0xFFFFFFFF);
        context.guiRenderState.up();
        context.fill(cursor, barY, cursor + 1, barY + 3, c.getColor());
    }

    private static void renderText(GuiGraphicsExtractor context, Font font, TextSetting t,
                                   int x, int y, int w, int nameColor, int ty, Host host) {
        context.text(font, t.getName(), x + PAD, ty, nameColor, false);
        boolean editing = host.isEditing(t);
        int room = w - 2 * PAD - 6 - font.width(t.getName());
        if (editing) {
            host.getEditField().render(context, font, x + w - PAD - room, ty, room,
                GuiTheme.accentText(), true);
            return;
        }
        String value = t.isBlank() ? "click to type" : t.getValue();
        // Long text keeps its tail visible.
        while (font.width(value) > room && value.length() > 2) {
            value = value.substring(1);
        }
        context.text(font, value, x + w - PAD - font.width(value), ty,
            t.isBlank() ? GuiTheme.TEXT_FAINT : GuiTheme.TEXT, false);
    }

    public static int blockHeight(Module module) {
        return (visibleCount(module) + 1) * GuiTheme.SETTING_HEIGHT + 4;
    }

    public static int blockContentX(int rowX) {
        return rowX + INDENT + 3;
    }

    public static int blockContentWidth(int rowW) {
        return rowW - INDENT - 5;
    }

    // Pairs with clickBlock.
    public static void renderBlock(GuiGraphicsExtractor context, Font font, Module module,
                                   int rowX, int rowY, int rowW, int mouseX, int mouseY,
                                   boolean hoverAllowed, Host host) {
        int x = rowX + INDENT;
        int right = rowX + rowW - 2;
        int bottom = rowY + blockHeight(module) - 2;
        RenderUtil.roundedRect(context, x, rowY, right, bottom, GuiTheme.CORNER,
            GuiTheme.BG_SETTING, false, true);
        context.fill(x, rowY, x + 1, bottom, GuiTheme.accentOn(GuiTheme.BG_SETTING, 0.55f));
        context.guiRenderState.up();

        int cx = blockContentX(rowX);
        int cw = blockContentWidth(rowW);
        int y = rowY + 2;
        List<Setting<?>> settings = module.getSettings();
        for (int i = 0; i < settings.size(); i++) {
            Setting<?> setting = settings.get(i);
            if (!setting.isVisible()) {
                continue;
            }
            render(context, font, setting, cx, y, cw, mouseX, mouseY, hoverAllowed, host);
            y += GuiTheme.SETTING_HEIGHT;
        }
        renderBind(context, font, module.getKeybind(), cx, y, cw, mouseX, mouseY, hoverAllowed, host);
    }

    // Pairs with renderBlock.
    public static boolean clickBlock(Module module, double mx, double my, int rowX, int rowY,
                                     int rowW, int button, Host host, Drag drag) {
        int cx = blockContentX(rowX);
        int cw = blockContentWidth(rowW);
        int y = rowY + 2;
        for (Setting<?> setting : module.getSettings()) {
            if (!setting.isVisible()) {
                continue;
            }
            if (isOver(mx, my, cx, y, cw, GuiTheme.SETTING_HEIGHT)) {
                click(setting, mx, my, cx, y, cw, button, host, drag);
                return true;
            }
            y += GuiTheme.SETTING_HEIGHT;
        }
        if (isOver(mx, my, cx, y, cw, GuiTheme.SETTING_HEIGHT)) {
            host.startListening(module.getKeybind());
            return true;
        }
        return false;
    }

    public static void renderBind(GuiGraphicsExtractor context, Font font, KeybindSetting bind,
                                  int x, int y, int width, int mouseX, int mouseY,
                                  boolean hoverAllowed, Host host) {
        int h = GuiTheme.SETTING_HEIGHT;
        boolean hovered = hoverAllowed && isOver(mouseX, mouseY, x, y, width, h);
        if (hovered) {
            host.setTooltip(BIND_HELP);
            context.fill(x, y, x + width, y + h, 0x14FFFFFF);
            context.guiRenderState.up();
        }
        boolean listening = host.isBinding(bind);
        context.text(font, "Bind", x + PAD, GuiTheme.textY(y, h),
            hovered ? GuiTheme.TEXT : GuiTheme.TEXT_DIM, false);
        keyChip(context, font, x + width - PAD, y,
            listening ? "press a key" : bind.getKeyName(), listening);
    }

    // The chip ends at the given right edge.
    private static void keyChip(GuiGraphicsExtractor context, Font font, int right, int y,
                                String label, boolean listening) {
        int w = font.width(label) + 8;
        int h = GuiTheme.SETTING_HEIGHT - 4;
        int left = right - w;
        int top = y + 2;
        RenderUtil.roundedRect(context, left, top, right, top + h, 2,
            listening ? GuiTheme.accentOn(GuiTheme.BG_ROW, 0.5f) : GuiTheme.BG_ROW_HOVER);
        context.guiRenderState.up();
        context.text(font, label, left + 4, GuiTheme.textY(top, h),
            listening ? GuiTheme.accentText() : GuiTheme.TEXT, false);
    }

    // The hit test has already been done by the caller.
    public static void click(Setting<?> setting, double mx, double my, int x, int y, int width,
                             int button, Host host, Drag drag) {
        switch (setting) {
            case KeybindSetting k -> {
                host.startListening(k);
                return;
            }
            case BoolSetting b -> b.toggle();
            case NumberSetting n -> {
                if (button == 0 && overNumberValue(OfflineClient.MC.font, mx, my, n, x, y, width)) {
                    host.startEditing(n);
                    return;
                }
                n.beginSlider();
                drag.slider = n;
                n.setFromSlider(fraction(x, width, mx));
            }
            case EnumSetting<?> e -> e.cycle(button == 0);
            case ColorSetting c -> {
                if (button == 1 || overRainbowChip(OfflineClient.MC.font, mx, my, x, y, width)) {
                    c.setRainbow(!c.isRainbow());
                } else {
                    c.setRainbow(false);
                    drag.hue = c;
                    c.setHue((float) fraction(x, width, mx) * 360f);
                }
            }
            case RegistryListSetting<?> r -> host.openPicker(r);
            case TextSetting t -> {
                host.startEditing(t);
                return;
            }
            default -> {
            }
        }
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }

    public static String trimEnd(Font font, String text, int room) {
        if (font.width(text) <= room) {
            return text;
        }
        String value = text;
        while (font.width(value) > room && value.length() > 2) {
            value = value.substring(0, value.length() - 2) + ".";
        }
        return value;
    }

    private static void checkbox(GuiGraphicsExtractor context, int x, int y, boolean on) {
        RenderUtil.roundedBorderedRect(context, x, y, x + BOX, y + BOX, 2,
            on ? GuiTheme.accent() : GuiTheme.BG_SETTING, on ? GuiTheme.accent() : GuiTheme.EDGE);
        context.guiRenderState.up();
        if (on) {
            RenderUtil.tick(context, x + 2, y + 2, GuiTheme.contrastText(GuiTheme.accent()));
        }
    }

    private static void box(GuiGraphicsExtractor context, int x, int y, int size, int color) {
        RenderUtil.roundedBorderedRect(context, x, y, x + size, y + size, 2,
            color, GuiTheme.OUTLINE);
    }
}
