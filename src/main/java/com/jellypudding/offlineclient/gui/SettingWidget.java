package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.PickList;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

// Draws one setting row and turns clicks on it into changes.
public final class SettingWidget {

    private static final int PAD = GuiTheme.PAD;

    private static final int VALUE_BAND = GuiTheme.SETTING_HEIGHT - 5;
    private static final int INDENT = 10;
    private static final int BOX = 9;

    // Each level of sub option steps in by this much with a guide line beside it.
    private static final int SUB_INDENT = 8;

    // The chevron gutter on a row that has sub options beneath it.
    private static final int FOLD_ZONE = 8;

    // A colour row carries a hue bar then a saturation bar then a brightness bar.
    private static final int COLOR_BARS = 3;
    private static final int BAR_HEIGHT = 3;
    private static final int BAR_PITCH = 5;
    private static final int HUE_CHANNEL = 0;
    private static final int SATURATION_CHANNEL = 1;
    private static final int BRIGHTNESS_CHANNEL = 2;

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

        void openPicker(PickList<?> setting);
    }

    // A slider or colour bar keeps following the mouse even when it leaves the row.
    public static final class Drag {

        private NumberSetting slider;
        private ColorSetting color;
        private int channel;

        // How far the row being dragged sits in from the block edge.
        private int indent;

        public boolean isActive() {
            return slider != null || color != null;
        }

        // Called every frame with the geometry of the settings block.
        public void follow(int x, int width, int mouseX) {
            if (slider != null) {
                slider.setFromSlider(fraction(x + indent, width - indent, mouseX));
            }
            if (color != null) {
                setChannel(color, channel, (float) fraction(x + indent, width - indent, mouseX));
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
            color = null;
        }
    }

    // A colour row needs room under the hue bar for the two extra bars.
    public static int rowHeight(Setting<?> setting) {
        if (setting instanceof ColorSetting) {
            return GuiTheme.SETTING_HEIGHT + (COLOR_BARS - 1) * BAR_PITCH;
        }
        return GuiTheme.SETTING_HEIGHT;
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
        int h = rowHeight(setting);
        int ty = GuiTheme.textY(y, GuiTheme.SETTING_HEIGHT);
        // Rows with a bar at the bottom centre their text in the band above it.
        int bandY = GuiTheme.textY(y, VALUE_BAND);
        boolean hovered = hoverAllowed && isOver(mouseX, mouseY, x, y, w, h);
        if (hovered) {
            // A bind row wants the help text and not the description of the key.
            host.setTooltip(setting instanceof KeybindSetting
                ? BIND_HELP : setting.getDescription());
            context.fill(x, y, x + w, y + h, 0x14FFFFFF);
            context.guiRenderState.up();
        }
        int nameColor = hovered ? GuiTheme.text() : GuiTheme.textDim();

        switch (setting) {
            case BoolSetting b -> {
                context.text(font, trimEnd(font, b.getName(), w - 2 * PAD - BOX - 4),
                    x + PAD, ty, nameColor, false);
                checkbox(context, x + w - PAD - BOX, y + (h - BOX) / 2, b.isOn());
            }
            case NumberSetting n -> {
                int room = w - 2 * PAD - font.width(n.getValueString()) - 6;
                context.text(font, trimEnd(font, n.getName(), room), x + PAD, bandY, nameColor, false);
                renderNumber(context, font, n, x, y, w, mouseX, mouseY, hoverAllowed, host);
            }
            case KeybindSetting k -> {
                String label = host.isBinding(k) ? "press a key" : k.getKeyName();
                int room = w - 2 * PAD - font.width(label) - 12;
                context.text(font, trimEnd(font, k.getName(), room), x + PAD, ty, nameColor, false);
                keyChip(context, font, x + w - PAD, y, label, host.isBinding(k));
            }
            case EnumSetting<?> e -> {
                context.text(font, e.getName(), x + PAD, ty, nameColor, false);
                String value = trimEnd(font, e.getValueString(),
                    w - 2 * PAD - 6 - font.width(e.getName()));
                context.text(font, value, x + w - PAD - font.width(value), ty,
                    GuiTheme.accentText(), false);
            }
            case ColorSetting c -> {
                int room = w - 2 * PAD - BOX - rainbowChipWidth(font) - 8;
                context.text(font, trimEnd(font, c.getName(), room), x + PAD, bandY, nameColor, false);
                renderColor(context, font, c, x, y, w);
            }
            case PickList<?> r -> {
                String value = r.size() + " chosen";
                int room = w - 2 * PAD - font.width(value) - 6;
                context.text(font, trimEnd(font, r.getName(), room), x + PAD, ty, nameColor, false);
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
        int highlight = editing || overValue ? GuiTheme.accentText() : GuiTheme.text();
        int valueY = GuiTheme.textY(y, VALUE_BAND);
        int right = x + w - PAD;
        int valueX;
        int underlineX;
        if (editing) {
            // The typed number gets the value area the name has left free.
            int room = Math.max(12, w - 2 * PAD - 6 - font.width(n.getName()));
            // A number being typed grows to the left. The caret stays put.
            int shown = Math.min(room, font.width(host.getEditField().get()));
            valueX = right - shown;
            host.getEditField().render(context, font, valueX, valueY, shown, highlight, true);
            // An empty box still needs a mark to aim at.
            underlineX = Math.min(valueX, right - 6);
        } else {
            String value = n.getValueString();
            valueX = right - font.width(value);
            context.text(font, value, valueX, valueY, highlight, false);
            underlineX = valueX;
        }
        context.fill(underlineX, y + VALUE_BAND - 1, right, y + VALUE_BAND,
            editing || overValue ? GuiTheme.accentText() : GuiTheme.scrollThumb());

        int barY = y + GuiTheme.SETTING_HEIGHT - 4;
        int barW = w - 2 * PAD;
        RenderUtil.roundedRect(context, x + PAD, barY, x + PAD + barW, barY + 3, 1,
            GuiTheme.bgRowHover());
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

    // Top of the hue bar at index zero and of the two bars stacked below it.
    private static int barTop(int y, int index) {
        return y + GuiTheme.SETTING_HEIGHT - 4 + index * BAR_PITCH;
    }

    // Which bar the pointer sits on. Each bar owns the gap beneath it.
    private static int barIndex(double my, int y) {
        double offset = (my - barTop(y, HUE_CHANNEL)) / BAR_PITCH;
        return (int) Math.clamp(offset, 0, COLOR_BARS - 1);
    }

    private static void setChannel(ColorSetting c, int channel, float amount) {
        switch (channel) {
            case SATURATION_CHANNEL -> c.setSaturation(amount);
            case BRIGHTNESS_CHANNEL -> c.setBrightness(amount);
            default -> c.setHue(amount * 360f);
        }
    }

    private static void renderColor(GuiGraphicsExtractor context, Font font, ColorSetting c,
                                    int x, int y, int w) {
        int chipX = rainbowChipX(font, x, w);
        int chipW = rainbowChipWidth(font);
        RenderUtil.roundedBorderedRect(context, chipX, y + 1, chipX + chipW, y + 1 + BOX, 2,
            c.isRainbow() ? GuiTheme.accentOn(GuiTheme.bgSetting(), 0.7f) : GuiTheme.bgRow(),
            c.isRainbow() ? GuiTheme.accent() : GuiTheme.edge());
        context.guiRenderState.up();
        context.text(font, RAINBOW_LABEL, chipX + 3, y + 1 + (BOX - GuiTheme.TEXT_HEIGHT) / 2,
            c.isRainbow() ? GuiTheme.text() : GuiTheme.textFaint(), false);

        box(context, x + w - PAD - BOX, y + 1, BOX, c.getColor());
        int barX = x + PAD;
        int barW = w - 2 * PAD;
        RenderUtil.hueBar(context, barX, barTop(y, HUE_CHANNEL), barW, BAR_HEIGHT);
        // Saturation runs from the grey of the current brightness to the pure hue.
        ramp(context, barX, barTop(y, SATURATION_CHANNEL), barW,
            ColorUtil.hsv(0f, 0f, c.getBrightness()),
            ColorUtil.hsv(c.getHue(), 1f, c.getBrightness()));
        // Brightness runs from black up to the hue at the current saturation.
        ramp(context, barX, barTop(y, BRIGHTNESS_CHANNEL), barW, 0xFF000000,
            ColorUtil.hsv(c.getHue(), c.getSaturation(), 1f));
        context.guiRenderState.up();
        if (c.isRainbow()) {
            return;
        }
        int marker = c.getColor();
        barCursor(context, barX, barTop(y, HUE_CHANNEL), barW, c.getHue() / 360f, marker);
        barCursor(context, barX, barTop(y, SATURATION_CHANNEL), barW, c.getSaturation(), marker);
        barCursor(context, barX, barTop(y, BRIGHTNESS_CHANNEL), barW, c.getBrightness(), marker);
    }

    // Horizontal blend between two colours. The vanilla gradient fill only runs top to bottom.
    private static void ramp(GuiGraphicsExtractor context, int x, int y, int width,
                             int from, int to) {
        int band = 2;
        int last = Math.max(1, width - band);
        for (int i = 0; i < width; i += band) {
            int end = Math.min(width, i + band);
            context.fill(x + i, y, x + end, y + BAR_HEIGHT,
                ColorUtil.lerp(from, to, (float) i / last));
        }
    }

    // A white notch with the chosen colour in the middle of it.
    private static void barCursor(GuiGraphicsExtractor context, int x, int y, int width,
                                  float fraction, int color) {
        int at = Math.clamp(x + (int) (width * fraction), x, x + width - 1);
        context.fill(at - 1, y - 1, at + 2, y + BAR_HEIGHT + 1, 0xFFFFFFFF);
        context.guiRenderState.up();
        context.fill(at, y, at + 1, y + BAR_HEIGHT, color);
    }

    private static void renderText(GuiGraphicsExtractor context, Font font, TextSetting t,
                                   int x, int y, int w, int nameColor, int ty, Host host) {
        // The name gives way first. The value keeps at least half the row.
        String name = trimEnd(font, t.getName(), (w - 2 * PAD) / 2);
        context.text(font, name, x + PAD, ty, nameColor, false);
        boolean editing = host.isEditing(t);
        int room = w - 2 * PAD - 6 - font.width(name);
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
            t.isBlank() ? GuiTheme.textFaint() : GuiTheme.text(), false);
    }

    public static int blockHeight(Module module) {
        int height = GuiTheme.SETTING_HEIGHT + 4;
        List<Setting<?>> settings = module.getSettings();
        Layout layout = layout(settings);
        for (int i = 0; i < settings.size(); i++) {
            if (layout.shown[i]) {
                height += rowHeight(settings.get(i));
            }
        }
        return height;
    }

    // Where each row of a block sits. A row is shown whilst it is visible and no
    // setting above it in its family is folded. A parent row carries the chevron.
    private record Layout(int[] indent, boolean[] shown, boolean[] parent) {
    }

    // A sub option only steps in whilst it follows its parent or sibling
    // directly and reads as its own row otherwise.
    private static Layout layout(List<Setting<?>> settings) {
        int count = settings.size();
        Layout layout = new Layout(new int[count], new boolean[count], new boolean[count]);
        // The previous row and its ancestors from the top down with their indexes.
        List<Setting<?>> open = new ArrayList<>();
        List<Integer> openAt = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Setting<?> setting = settings.get(i);
            if (!setting.isVisible()) {
                continue;
            }
            int depth = open.indexOf(setting.getParent()) + 1;
            while (open.size() > depth) {
                open.removeLast();
                openAt.removeLast();
            }
            layout.indent[i] = depth * SUB_INDENT;
            layout.shown[i] = open.stream().noneMatch(Setting::isFolded);
            if (depth > 0) {
                layout.parent[openAt.get(depth - 1)] = true;
            }
            open.add(setting);
            openAt.add(i);
        }
        return layout;
    }

    // The chevron that folds a family away. Lit whilst the pointer is on it.
    private static void foldMarker(GuiGraphicsExtractor context, Setting<?> setting, int x, int y,
                                   int w, int h, int mouseX, int mouseY, boolean hoverAllowed) {
        boolean rowHovered = hoverAllowed && isOver(mouseX, mouseY, x, y, w, h);
        boolean hovered = rowHovered && mouseX < x + FOLD_ZONE;
        if (rowHovered) {
            context.fill(x, y, x + FOLD_ZONE, y + h, 0x14FFFFFF);
            context.guiRenderState.up();
        }
        RenderUtil.chevron(context, x + 1, y + (GuiTheme.SETTING_HEIGHT - 3) / 2, setting.isFolded(),
            hovered ? GuiTheme.text() : GuiTheme.textFaint());
    }

    // A hairline down the side of a sub option that ties it to its parent.
    private static void subGuide(GuiGraphicsExtractor context, int x, int y, int indent, int h) {
        int guideX = x + PAD + indent - SUB_INDENT + 1;
        context.fill(guideX, y, guideX + 1, y + h, GuiTheme.accentOn(GuiTheme.bgSetting(), 0.45f));
        context.guiRenderState.up();
    }

    public static int blockContentX(int rowX) {
        return rowX + INDENT + 3;
    }

    public static int blockContentWidth(int rowW) {
        return rowW - INDENT - 5;
    }

    public static void renderBlock(GuiGraphicsExtractor context, Font font, Module module,
                                   int rowX, int rowY, int rowW, int mouseX, int mouseY,
                                   boolean hoverAllowed, Host host) {
        int x = rowX + INDENT;
        int right = rowX + rowW - 2;
        int bottom = rowY + blockHeight(module) - 2;
        RenderUtil.roundedRect(context, x, rowY, right, bottom, GuiTheme.CORNER,
            GuiTheme.bgSetting(), false, true);
        context.fill(x, rowY, x + 1, bottom, GuiTheme.accentOn(GuiTheme.bgSetting(), 0.55f));
        context.guiRenderState.up();

        int cx = blockContentX(rowX);
        int cw = blockContentWidth(rowW);
        int y = rowY + 2;
        List<Setting<?>> settings = module.getSettings();
        Layout layout = layout(settings);
        for (int i = 0; i < settings.size(); i++) {
            if (!layout.shown[i]) {
                continue;
            }
            Setting<?> setting = settings.get(i);
            int h = rowHeight(setting);
            int indent = layout.indent[i];
            if (indent > 0) {
                subGuide(context, cx, y, indent, h);
            }
            int sx = cx + indent;
            int sw = cw - indent;
            if (layout.parent[i]) {
                foldMarker(context, setting, sx, y, sw, h, mouseX, mouseY, hoverAllowed);
                sx += FOLD_ZONE;
                sw -= FOLD_ZONE;
            }
            render(context, font, setting, sx, y, sw, mouseX, mouseY, hoverAllowed, host);
            y += h;
        }
        // The bind of the module itself closes the block.
        render(context, font, module.getKeybind(), cx, y, cw, mouseX, mouseY, hoverAllowed, host);
    }

    public static boolean clickBlock(Module module, double mx, double my, int rowX, int rowY,
                                     int rowW, int button, Host host, Drag drag) {
        int cx = blockContentX(rowX);
        int cw = blockContentWidth(rowW);
        int y = rowY + 2;
        List<Setting<?>> settings = module.getSettings();
        Layout layout = layout(settings);
        for (int i = 0; i < settings.size(); i++) {
            if (!layout.shown[i]) {
                continue;
            }
            Setting<?> setting = settings.get(i);
            int h = rowHeight(setting);
            if (isOver(mx, my, cx, y, cw, h)) {
                // The gutter beside a sub option still belongs to its row.
                int sx = cx + layout.indent[i];
                if (layout.parent[i]) {
                    if (mx < sx + FOLD_ZONE) {
                        setting.setFolded(!setting.isFolded());
                        OfflineClient.INSTANCE.getConfigManager().saveSoon();
                        return true;
                    }
                    sx += FOLD_ZONE;
                }
                drag.indent = sx - cx;
                click(setting, mx, my, sx, y, cw - (sx - cx), button, host, drag);
                return true;
            }
            y += h;
        }
        if (isOver(mx, my, cx, y, cw, GuiTheme.SETTING_HEIGHT)) {
            click(module.getKeybind(), mx, my, cx, y, cw, button, host, drag);
            return true;
        }
        return false;
    }

    // The chip ends at the given right edge.
    private static void keyChip(GuiGraphicsExtractor context, Font font, int right, int y,
                                String label, boolean listening) {
        int w = font.width(label) + 8;
        int h = GuiTheme.SETTING_HEIGHT - 4;
        int left = right - w;
        int top = y + 2;
        RenderUtil.roundedRect(context, left, top, right, top + h, 2,
            listening ? GuiTheme.accentOn(GuiTheme.bgRow(), 0.5f) : GuiTheme.bgRowHover());
        context.guiRenderState.up();
        context.text(font, label, left + 4, GuiTheme.textY(top, h),
            listening ? GuiTheme.accentText() : GuiTheme.text(), false);
    }

    // The hit test has already been done by the caller.
    public static void click(Setting<?> setting, double mx, double my, int x, int y, int width,
                             int button, Host host, Drag drag) {
        // The middle button puts a setting back to how it started.
        if (button == 2) {
            setting.reset();
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
            return;
        }
        // Only the left and the right button act on a setting.
        if (button != 0 && button != 1) {
            return;
        }
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
                } else if (my >= barTop(y, HUE_CHANNEL)) {
                    c.setRainbow(false);
                    drag.color = c;
                    drag.channel = barIndex(my, y);
                    setChannel(c, drag.channel, (float) fraction(x, width, mx));
                } else {
                    // The name band holds the preview and the chip. Neither is a bar.
                    return;
                }
            }
            case PickList<?> r -> host.openPicker(r);
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
        // Nothing beats a lone dot.
        if (room < font.width("..")) {
            return "";
        }
        String value = text;
        while (font.width(value) > room && value.length() > 2) {
            value = value.substring(0, value.length() - 2) + ".";
        }
        return value;
    }

    private static void checkbox(GuiGraphicsExtractor context, int x, int y, boolean on) {
        RenderUtil.roundedBorderedRect(context, x, y, x + BOX, y + BOX, 2,
            on ? GuiTheme.accent() : GuiTheme.bgSetting(), on ? GuiTheme.accent() : GuiTheme.edge());
        context.guiRenderState.up();
        if (on) {
            RenderUtil.tick(context, x + 2, y + 2, GuiTheme.contrastText(GuiTheme.accent()));
        }
    }

    private static void box(GuiGraphicsExtractor context, int x, int y, int size, int color) {
        RenderUtil.roundedBorderedRect(context, x, y, x + size, y + size, 2,
            color, GuiTheme.outline());
    }
}
