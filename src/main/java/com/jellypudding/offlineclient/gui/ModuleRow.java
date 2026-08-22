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
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * One module entry inside a panel. Left click toggles the module and the
 * marker on the right or a right click expands its settings.
 */
public final class ModuleRow {

    private final Module module;
    private final ClickGuiScreen screen;
    private boolean expanded;

    // Position and size assigned by the panel each frame.
    private int x;
    private int y;
    private int width = GuiTheme.PANEL_WIDTH;

    // Whether hover effects and tooltips are allowed this frame. Dragging
    // still uses the real mouse position even outside the panel.
    private boolean hoverActive = true;

    private NumberSetting draggingSlider;
    private ColorSetting draggingHue;
    private boolean listeningForBind;

    public ModuleRow(Module module, ClickGuiScreen screen) {
        this.module = module;
        this.screen = screen;
    }

    public Module getModule() {
        return module;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    public boolean isListeningForBind() {
        return listeningForBind;
    }

    public void setListeningForBind(boolean listening) {
        listeningForBind = listening;
    }

    public int getHeight() {
        if (!expanded) {
            return GuiTheme.ROW_HEIGHT;
        }
        return GuiTheme.ROW_HEIGHT
            + (visibleSettings().size() + 1) * GuiTheme.SETTING_HEIGHT + 2;
    }

    private List<Setting<?>> visibleSettings() {
        List<Setting<?>> visible = new ArrayList<>();
        for (Setting<?> setting : module.getSettings()) {
            if (setting.isVisible()) {
                visible.add(setting);
            }
        }
        return visible;
    }

    public void render(GuiGraphicsExtractor context, int x, int y, int width,
                       int mouseX, int mouseY, boolean hoverAllowed) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.hoverActive = hoverAllowed;
        Font font = OfflineClient.MC.font;
        int w = width;

        boolean hovered = hoverAllowed && isOver(mouseX, mouseY, x, y, w, GuiTheme.ROW_HEIGHT);
        int bg = module.isEnabled()
            ? ColorUtil.lerp(GuiTheme.BG_ROW, GuiTheme.accent(), hovered ? 0.45f : 0.35f)
            : (hovered ? GuiTheme.BG_ROW_HOVER : GuiTheme.BG_ROW);
        context.fill(x, y, x + w, y + GuiTheme.ROW_HEIGHT, bg);

        context.guiRenderState.up();
        int textColor = module.isEnabled() ? GuiTheme.TEXT : GuiTheme.TEXT_DIM;
        context.text(font, module.getName(), x + 5, y + 3, textColor, false);
        String marker = expanded ? "-" : "+";
        context.text(font, marker, x + w - 8, y + 3, GuiTheme.TEXT_DIM, false);

        if (hovered) {
            screen.setTooltip(module.getDescription());
        }

        if (expanded) {
            renderSettings(context, mouseX, mouseY);
        }
    }

    private void renderSettings(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        Font font = OfflineClient.MC.font;
        int w = width;
        int sy = y + GuiTheme.ROW_HEIGHT;
        List<Setting<?>> visible = visibleSettings();
        int totalHeight = (visible.size() + 1) * GuiTheme.SETTING_HEIGHT + 2;
        context.fill(x, sy, x + w, sy + totalHeight, GuiTheme.BG_SETTING);
        context.fill(x + 1, sy, x + 2, sy + totalHeight, GuiTheme.accent());
        context.guiRenderState.up();

        // Keep dragged sliders following the mouse even off the row.
        if (draggingSlider != null) {
            applySlider(draggingSlider, mouseX);
        }
        if (draggingHue != null) {
            draggingHue.setHue((float) sliderFraction(mouseX) * 360f);
        }

        for (Setting<?> setting : visible) {
            renderSetting(context, font, setting, sy, mouseX, mouseY);
            sy += GuiTheme.SETTING_HEIGHT;
        }

        // Bind row.
        KeybindSetting bind = module.getKeybind();
        String bindText = listeningForBind ? "press a key..." : bind.getKeyName();
        context.text(font, "Bind", x + 6, sy + 3, GuiTheme.TEXT_DIM, false);
        int color = listeningForBind ? GuiTheme.accent() : GuiTheme.TEXT;
        context.text(font, bindText, x + w - 6 - font.width(bindText), sy + 3, color, false);
        if (hoverActive && isOver(mouseX, mouseY, x, sy, w, GuiTheme.SETTING_HEIGHT)) {
            screen.setTooltip("Click here and then press a key. DELETE unbinds. ESC cancels.");
        }
    }

    private void renderSetting(GuiGraphicsExtractor context, Font font, Setting<?> setting,
                               int sy, int mouseX, int mouseY) {
        int w = width;
        if (hoverActive && isOver(mouseX, mouseY, x, sy, w, GuiTheme.SETTING_HEIGHT)) {
            screen.setTooltip(setting.getDescription());
        }

        switch (setting) {
            case BoolSetting b -> {
                context.text(font, b.getName(), x + 6, sy + 3, GuiTheme.TEXT_DIM, false);
                int boxX = x + w - 14;
                RenderUtilBox.box(context, boxX, sy + 3, 8,
                    b.isOn() ? GuiTheme.accent() : GuiTheme.BG_ROW_HOVER);
            }
            case NumberSetting n -> {
                context.text(font, n.getName(), x + 6, sy + 2, GuiTheme.TEXT_DIM, false);
                boolean editing = screen.isEditing(n);
                String value = editing ? screen.getEditBuffer() + "_" : n.getValueString();
                int valueX = x + w - 6 - font.width(value);
                boolean overValue = hoverActive
                    && isOver(mouseX, mouseY, valueX - 2, sy, w - (valueX - x) + 2, 10);
                int valueColor = editing || overValue ? GuiTheme.accent() : GuiTheme.TEXT;
                context.text(font, value, valueX, sy + 2, valueColor, false);
                context.fill(valueX, sy + 10, x + w - 6, sy + 11,
                    overValue || editing ? GuiTheme.accent() : GuiTheme.TEXT_DIM);
                int barY = sy + GuiTheme.SETTING_HEIGHT - 3;
                context.fill(x + 6, barY, x + w - 6, barY + 2, GuiTheme.BG_ROW_HOVER);
                int fill = (int) ((w - 12) * n.getSliderFraction());
                context.guiRenderState.up();
                context.fill(x + 6, barY, x + 6 + fill, barY + 2, GuiTheme.accent());
            }
            case EnumSetting<?> e -> {
                context.text(font, e.getName(), x + 6, sy + 3, GuiTheme.TEXT_DIM, false);
                String value = String.valueOf(e.getValue());
                int room = w - 14 - font.width(e.getName());
                while (font.width(value) > room && value.length() > 2) {
                    value = value.substring(0, value.length() - 2) + ".";
                }
                context.text(font, value, x + w - 6 - font.width(value), sy + 3, GuiTheme.accent(), false);
            }
            case ColorSetting c -> {
                context.text(font, c.getName(), x + 6, sy + 2, GuiTheme.TEXT_DIM, false);
                RenderUtilBox.box(context, x + w - 14, sy + 2, 8, c.getColor());
                int barY = sy + GuiTheme.SETTING_HEIGHT - 3;
                for (int i = 0; i < w - 12; i++) {
                    int hueColor = ColorUtil.hsv(360f * i / (w - 12), 0.75f, 1f);
                    context.fill(x + 6 + i, barY, x + 7 + i, barY + 2, hueColor);
                }
                if (!c.isRainbow()) {
                    int cursor = x + 6 + (int) ((w - 12) * (c.getHue() / 360f));
                    context.guiRenderState.up();
                    context.fill(cursor - 1, barY - 1, cursor + 1, barY + 3, 0xFFFFFFFF);
                }
            }
            case RegistryListSetting<?> r -> {
                context.text(font, r.getName(), x + 6, sy + 3, GuiTheme.TEXT_DIM, false);
                String value = r.size() + " chosen";
                context.text(font, value, x + w - 6 - font.width(value), sy + 3, GuiTheme.accent(), false);
            }
            case TextSetting t -> {
                context.text(font, t.getName(), x + 6, sy + 3, GuiTheme.TEXT_DIM, false);
                boolean editing = screen.isEditing(t);
                String value = editing ? screen.getEditBuffer() + "_"
                    : t.isBlank() ? "click to type" : t.getValue();
                // Long text keeps its tail visible.
                int room = w - 14 - font.width(t.getName());
                while (font.width(value) > room && value.length() > 2) {
                    value = value.substring(1);
                }
                int color = editing ? GuiTheme.accent() : t.isBlank() ? GuiTheme.TEXT_DIM : GuiTheme.TEXT;
                context.text(font, value, x + w - 6 - font.width(value), sy + 3, color, false);
            }
            default -> context.text(font, setting.getName(), x + 6, sy + 3, GuiTheme.TEXT_DIM, false);
        }
    }

    /** Returns true if the click was handled. */
    public boolean mouseClicked(double mx, double my, int button) {
        int w = width;

        if (isOver(mx, my, x, y, w, GuiTheme.ROW_HEIGHT)) {
            // The right marker and right clicks expand. The rest of the
            // row toggles. Untogglable rows always expand.
            if (button == 0 && mx >= x + w - 14) {
                expanded = !expanded;
            } else if (button == 0 && !module.isTogglable()) {
                expanded = !expanded;
            } else if (button == 0) {
                module.toggle();
                OfflineClient.INSTANCE.getConfigManager().saveSoon();
            } else if (button == 1) {
                expanded = !expanded;
            }
            return true;
        }

        if (!expanded) {
            return false;
        }

        int sy = y + GuiTheme.ROW_HEIGHT;
        for (Setting<?> setting : visibleSettings()) {
            if (isOver(mx, my, x, sy, w, GuiTheme.SETTING_HEIGHT)) {
                clickSetting(setting, mx, my, sy, button);
                return true;
            }
            sy += GuiTheme.SETTING_HEIGHT;
        }

        if (isOver(mx, my, x, sy, w, GuiTheme.SETTING_HEIGHT)) {
            screen.startListening(this);
            return true;
        }
        return false;
    }

    private void clickSetting(Setting<?> setting, double mx, double my, int sy, int button) {
        switch (setting) {
            case BoolSetting b -> b.toggle();
            case NumberSetting n -> {
                // Clicking the number itself opens typing mode. The bar at
                // the bottom and everywhere else drags the slider.
                boolean onBar = my >= sy + GuiTheme.SETTING_HEIGHT - 6;
                if (!onBar && button == 0 && mx >= x + width - 48) {
                    screen.startEditing(n);
                    return;
                }
                n.beginSlider();
                draggingSlider = n;
                applySlider(n, mx);
            }
            case EnumSetting<?> e -> e.cycle(button == 0);
            case ColorSetting c -> {
                if (button == 1) {
                    c.setRainbow(!c.isRainbow());
                } else {
                    c.setRainbow(false);
                    draggingHue = c;
                    c.setHue((float) sliderFraction(mx) * 360f);
                }
            }
            case RegistryListSetting<?> r -> screen.openPicker(r);
            case TextSetting t -> {
                screen.startEditing(t);
                return;
            }
            default -> {
            }
        }
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }

    private void applySlider(NumberSetting n, double mx) {
        n.setFromSlider(sliderFraction(mx));
    }

    private double sliderFraction(double mx) {
        return Math.clamp((mx - (x + 6)) / (width - 12.0), 0, 1);
    }

    public void mouseReleased() {
        if (draggingSlider != null || draggingHue != null) {
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
        }
        if (draggingSlider != null) {
            draggingSlider.endSlider();
        }
        draggingSlider = null;
        draggingHue = null;
    }

    public void collapse() {
        expanded = false;
        mouseReleased();
        listeningForBind = false;
    }

    private static boolean isOver(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Tiny helper for the checkbox/color squares. */
    private static final class RenderUtilBox {
        static void box(GuiGraphicsExtractor context, int x, int y, int size, int color) {
            context.fill(x, y, x + size, y + size, GuiTheme.OUTLINE);
            context.fill(x + 1, y + 1, x + size - 1, y + size - 1, color);
        }
    }
}
