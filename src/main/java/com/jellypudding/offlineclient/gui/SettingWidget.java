package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.GridSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.PickList;
import com.jellypudding.offlineclient.setting.RankSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

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

    // A ranked list gives each choice a row tall enough for its item.
    private static final int ENTRY_HEIGHT = 18;
    private static final int ICON = 16;
    private static final int GRIP_WIDTH = 3;
    private static final int GRIP_HEIGHT = 5;
    // How far a press on a choice travels before it counts as a drag.
    private static final int DRAG_SLOP = 3;
    // Laid over the item of a choice that is switched off.
    private static final int OFF_VEIL = 0xB0;

    // A painted grid keeps within this span. Small sizes get roomier cells up to the cap.
    private static final int GRID_SPAN = 72;
    private static final int MAX_CELL_PITCH = 12;
    private static final int CELL_GAP = 1;
    private static final int GRID_MARGIN = 3;
    // The dot that marks the middle cell.
    private static final int MIDDLE_DOT = 2;

    // Ends an action row. It runs something rather than holding a value.
    private static final String PRESS_MARK = "»";
    // The tick in front of what an action did and the gap after it.
    private static final int TICK_ROOM = 9;

    private static final String BIND_HELP =
        "Click here and then press a key. DELETE unbinds. ESC cancels.";
    private static final String RANK_HELP =
        "Drag a row up or down to change the order. Click it to switch it on or off.";
    private static final String GRID_HELP =
        "Click or drag to paint cells. Right click rubs them out.";

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
    // A choice in a ranked list follows it up and down. A press on a grid paints a stroke.
    public static final class Drag {

        private NumberSetting slider;
        private ColorSetting color;
        private int channel;

        private RankSetting<?> rank;
        private int rankIndex;
        private double pressY;
        // The top of the held list's first choice on screen.
        private double firstTop;
        // A press that never travels is a click.
        private boolean travelled;

        private GridSetting grid;
        private GridBox gridBox;
        private boolean paint;
        // Null whilst the pointer is off the grid.
        private GridSetting.Cell lastCell;

        // How far the row being dragged sits in from the block edge.
        private int indent;

        private boolean isActive() {
            return slider != null || color != null || rank != null || grid != null;
        }

        private void grab(RankSetting<?> setting, int index, double mouseY, int top) {
            rank = setting;
            rankIndex = index;
            pressY = mouseY;
            firstTop = top;
            travelled = false;
        }

        private void paint(GridSetting setting, GridBox box, GridSetting.Cell cell, boolean on) {
            grid = setting;
            gridBox = box;
            paint = on;
            lastCell = cell;
        }

        // Called every frame with the geometry of the settings block.
        public void follow(int x, int width, int mouseX, int mouseY) {
            if (slider != null) {
                slider.setFromSlider(fraction(x + indent, width - indent, mouseX));
            }
            if (color != null) {
                setChannel(color, channel, (float) fraction(x + indent, width - indent, mouseX));
            }
            if (rank != null) {
                followRank(mouseY);
            }
            if (grid != null) {
                followGrid(mouseX, mouseY);
            }
        }

        // Paints the cells between the last frame and this one. A quick stroke leaves no gaps.
        private void followGrid(int mouseX, int mouseY) {
            GridSetting.Cell cell = gridBox.cellAt(mouseX, mouseY);
            if (cell == null || cell.equals(lastCell)) {
                lastCell = cell;
                return;
            }
            GridSetting.Cell from = lastCell == null ? cell : lastCell;
            int steps = Math.max(1,
                Math.max(Math.abs(cell.column() - from.column()), Math.abs(cell.row() - from.row())));
            for (int step = 1; step <= steps; step++) {
                float share = (float) step / steps;
                grid.set(Math.round(from.column() + (cell.column() - from.column()) * share),
                    Math.round(from.row() + (cell.row() - from.row()) * share), paint);
            }
            lastCell = cell;
        }

        // The held choice moves into whichever row the pointer is over.
        private void followRank(int mouseY) {
            travelled |= Math.abs(mouseY - pressY) > DRAG_SLOP;
            if (!travelled) {
                return;
            }
            int target = Math.clamp((int) Math.floor((mouseY - firstTop) / ENTRY_HEIGHT), 0, rank.size() - 1);
            if (target != rankIndex) {
                rank.move(rankIndex, target);
                rankIndex = target;
            }
        }

        // Ends a hold without acting on the press. A screen closing mid press does this.
        public void cancel() {
            rank = null;
            release();
        }

        public void release() {
            if (rank != null && !travelled) {
                rank.toggle(rankIndex);
            }
            if (isActive()) {
                OfflineClient.INSTANCE.getConfigManager().saveSoon();
            }
            if (slider != null) {
                slider.endSlider();
            }
            slider = null;
            color = null;
            rank = null;
            grid = null;
            gridBox = null;
            lastCell = null;
        }
    }

    // A colour row needs room under the hue bar for the two extra bars. A ranked list
    // needs a row for each choice under its name and a grid its cells.
    private static int rowHeight(Setting<?> setting) {
        return switch (setting) {
            case ColorSetting ignored -> GuiTheme.SETTING_HEIGHT + (COLOR_BARS - 1) * BAR_PITCH;
            case RankSetting<?> rank -> GuiTheme.SETTING_HEIGHT + rank.size() * ENTRY_HEIGHT;
            case GridSetting grid -> GuiTheme.SETTING_HEIGHT + grid.size() * cellPitch(grid.size()) + GRID_MARGIN;
            default -> GuiTheme.SETTING_HEIGHT;
        };
    }

    // The rows below the name light up one part at a time.
    private static boolean litInParts(Setting<?> setting) {
        return setting instanceof RankSetting || setting instanceof GridSetting;
    }

    private static int cellPitch(int size) {
        return Math.min(MAX_CELL_PITCH, GRID_SPAN / size);
    }

    // Where the cells of a grid sit on screen. A narrow panel shrinks the cells to fit.
    private record GridBox(int left, int top, int pitch, int size) {

        private static GridBox of(GridSetting grid, int x, int y, int w) {
            int size = grid.size();
            int full = cellPitch(size);
            int pitch = Math.max(1, Math.min(full, (w - 2 * PAD) / size));
            int span = pitch * size;
            return new GridBox(x + (w - span) / 2, y + GuiTheme.SETTING_HEIGHT + (full * size - span) / 2,
                pitch, size);
        }

        // Null off the grid.
        private GridSetting.Cell cellAt(double mx, double my) {
            int column = (int) Math.floor((mx - left) / pitch);
            int row = (int) Math.floor((my - top) / pitch);
            return column >= 0 && column < size && row >= 0 && row < size
                ? new GridSetting.Cell(column, row) : null;
        }
    }

    private static int entryTop(int y, int index) {
        return y + GuiTheme.SETTING_HEIGHT + index * ENTRY_HEIGHT;
    }

    // Minus one over the name row.
    private static int entryAt(RankSetting<?> rank, double my, int y) {
        int index = (int) Math.floor((my - entryTop(y, 0)) / ENTRY_HEIGHT);
        return index >= 0 && index < rank.size() ? index : -1;
    }

    // A bind row wants the help text and not the description of the key. The choices
    // of a ranked list explain how to move them and the cells of a grid how to paint.
    private static String tooltipOf(Setting<?> setting, double my, int y) {
        return switch (setting) {
            case KeybindSetting ignored -> BIND_HELP;
            case RankSetting<?> rank when entryAt(rank, my, y) >= 0 -> RANK_HELP;
            case GridSetting ignored when my >= y + GuiTheme.SETTING_HEIGHT -> GRID_HELP;
            default -> setting.getDescription();
        };
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

    private static void render(GuiGraphicsExtractor context, Font font, Setting<?> setting,
                              int x, int y, int width, int mouseX, int mouseY,
                              boolean hoverAllowed, Host host) {
        int w = width;
        int h = rowHeight(setting);
        int ty = GuiTheme.textY(y, GuiTheme.SETTING_HEIGHT);
        // Rows with a bar at the bottom centre their text in the band above it.
        int bandY = GuiTheme.textY(y, VALUE_BAND);
        boolean hovered = hoverAllowed && isOver(mouseX, mouseY, x, y, w, h);
        if (hovered) {
            host.setTooltip(tooltipOf(setting, mouseY, y));
            int washed = litInParts(setting) ? GuiTheme.SETTING_HEIGHT : h;
            if (mouseY < y + washed) {
                context.fill(x, y, x + w, y + washed, GuiTheme.HOVER_WASH);
                context.guiRenderState.up();
            }
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
            case RankSetting<?> r -> {
                context.text(font, trimEnd(font, r.getName(), w - 2 * PAD), x + PAD, ty, nameColor, false);
                renderRank(context, font, r, x, y, w, mouseX, mouseY, hoverAllowed);
            }
            case GridSetting g -> {
                String size = g.size() + GuiTheme.CROSS + g.size();
                context.text(font, trimEnd(font, g.getName(), w - 2 * PAD - font.width(size) - 6),
                    x + PAD, ty, nameColor, false);
                context.text(font, size, x + w - PAD - font.width(size), ty, GuiTheme.accentText(), false);
                renderGrid(context, g, x, y, w, mouseX, mouseY, hoverAllowed);
            }
            case ActionSetting a -> renderAction(context, font, a, x, w, ty, nameColor, hovered);
            default -> context.text(font, setting.getName(), x + PAD, ty, nameColor, false);
        }
    }

    // Painted cells take the accent and the rest the row shade. The cell under the pointer
    // lights up and a dot marks the middle one.
    private static void renderGrid(GuiGraphicsExtractor context, GridSetting grid, int x, int y, int w,
                                   int mouseX, int mouseY, boolean hoverAllowed) {
        GridBox box = GridBox.of(grid, x, y, w);
        GridSetting.Cell over = hoverAllowed ? box.cellAt(mouseX, mouseY) : null;
        int cell = Math.max(1, box.pitch() - CELL_GAP);
        for (int row = 0; row < grid.size(); row++) {
            for (int column = 0; column < grid.size(); column++) {
                int left = box.left() + column * box.pitch();
                int top = box.top() + row * box.pitch();
                boolean lit = over != null && over.column() == column && over.row() == row;
                int colour = grid.isOn(column, row)
                    ? (lit ? GuiTheme.accentText() : GuiTheme.accent())
                    : (lit ? GuiTheme.bgRowHover() : GuiTheme.bgRow());
                RenderUtil.roundedRect(context, left, top, left + cell, top + cell, 1, colour);
            }
        }
        context.guiRenderState.up();
        int middle = grid.size() / 2;
        int dotX = box.left() + middle * box.pitch() + (cell - MIDDLE_DOT) / 2;
        int dotY = box.top() + middle * box.pitch() + (cell - MIDDLE_DOT) / 2;
        context.fill(dotX, dotY, dotX + MIDDLE_DOT, dotY + MIDDLE_DOT,
            grid.isOn(middle, middle) ? GuiTheme.contrastText(GuiTheme.accent()) : GuiTheme.textFaint());
    }

    // The name with a mark that says the row can be pressed. An armed row asks for the
    // second click in red and afterwards the row shows what the action did for a moment.
    private static void renderAction(GuiGraphicsExtractor context, Font font, ActionSetting action,
                                     int x, int w, int ty, int nameColor, boolean hovered) {
        boolean armed = action.isArmed();
        int room = w - 2 * PAD - font.width(PRESS_MARK) - 4;
        String result = action.result();
        if (result != null) {
            float strength = action.resultStrength();
            RenderUtil.tick(context, x + PAD, ty + 1, ColorUtil.fade(GuiTheme.GREEN, strength));
            context.text(font, trimEnd(font, result, room - TICK_ROOM), x + PAD + TICK_ROOM, ty,
                ColorUtil.lerp(nameColor, GuiTheme.GREEN, strength), false);
        } else {
            String label = armed ? "Click again to " + action.confirmWords() : action.getName();
            context.text(font, trimEnd(font, label, room), x + PAD, ty,
                armed ? GuiTheme.RED_TEXT : nameColor, false);
        }
        int markColor = armed ? GuiTheme.RED_TEXT : hovered ? GuiTheme.accentText() : GuiTheme.textFaint();
        context.text(font, PRESS_MARK, x + w - PAD - font.width(PRESS_MARK), ty, markColor, false);
    }

    // Each choice reads grip then item then name with its box on the right. A choice
    // that is switched off fades back.
    private static void renderRank(GuiGraphicsExtractor context, Font font, RankSetting<?> rank,
                                   int x, int y, int w, int mouseX, int mouseY, boolean hoverAllowed) {
        int boxX = x + w - PAD - BOX;
        for (int i = 0; i < rank.size(); i++) {
            int top = entryTop(y, i);
            boolean on = rank.get(i).on();
            boolean over = hoverAllowed && isOver(mouseX, mouseY, x, top, w, ENTRY_HEIGHT);
            if (over) {
                context.fill(x, top, x + w, top + ENTRY_HEIGHT, GuiTheme.HOVER_WASH);
                context.guiRenderState.up();
            }
            grip(context, x + PAD, top + (ENTRY_HEIGHT - GRIP_HEIGHT) / 2,
                over ? GuiTheme.text() : GuiTheme.textFaint());
            int textX = x + PAD + GRIP_WIDTH + 4;
            ItemStack icon = rank.icon(i);
            if (icon != null) {
                int iconY = top + (ENTRY_HEIGHT - ICON) / 2;
                context.item(icon, textX, iconY);
                if (!on) {
                    context.guiRenderState.up();
                    context.fill(textX, iconY, textX + ICON, iconY + ICON,
                        ColorUtil.withAlpha(GuiTheme.bgSetting(), OFF_VEIL));
                }
                textX += ICON + 3;
            }
            String label = trimEnd(font, rank.label(i), boxX - 4 - textX);
            context.text(font, label, textX, GuiTheme.textY(top, ENTRY_HEIGHT),
                on ? (over ? GuiTheme.text() : GuiTheme.textDim()) : GuiTheme.textFaint(), false);
            checkbox(context, boxX, top + (ENTRY_HEIGHT - BOX) / 2, on);
        }
    }

    // Two columns of three dots that say a row can be picked up.
    private static void grip(GuiGraphicsExtractor context, int x, int y, int color) {
        for (int row = 0; row < GRIP_HEIGHT; row += 2) {
            for (int column = 0; column < GRIP_WIDTH; column += 2) {
                context.fill(x + column, y + row, x + column + 1, y + row + 1, color);
            }
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
        return blockHeight(module.getSettings(), module.getKeybind());
    }

    public static int blockHeight(List<Setting<?>> settings, Setting<?> trailer) {
        int height = trailer == null ? 4 : GuiTheme.SETTING_HEIGHT + 4;
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
            context.fill(x, y, x + FOLD_ZONE, y + h, GuiTheme.HOVER_WASH);
            context.guiRenderState.up();
        }
        RenderUtil.chevron(context, x + 1, y + (GuiTheme.SETTING_HEIGHT - RenderUtil.CHEVRON_HEIGHT) / 2,
            setting.isFolded(), hovered ? GuiTheme.text() : GuiTheme.textFaint());
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
        renderBlock(context, font, module.getSettings(), module.getKeybind(), rowX, rowY, rowW,
            mouseX, mouseY, hoverAllowed, host);
    }

    public static void renderBlock(GuiGraphicsExtractor context, Font font,
                                   List<Setting<?>> settings, Setting<?> trailer,
                                   int rowX, int rowY, int rowW, int mouseX, int mouseY,
                                   boolean hoverAllowed, Host host) {
        int x = rowX + INDENT;
        int right = rowX + rowW - 2;
        int bottom = rowY + blockHeight(settings, trailer) - 2;
        RenderUtil.roundedRect(context, x, rowY, right, bottom, GuiTheme.CORNER,
            GuiTheme.bgSetting(), false, true);
        context.fill(x, rowY, x + 1, bottom, GuiTheme.accentOn(GuiTheme.bgSetting(), 0.55f));
        context.guiRenderState.up();

        int cx = blockContentX(rowX);
        int cw = blockContentWidth(rowW);
        int y = rowY + 2;
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
        if (trailer != null) {
            render(context, font, trailer, cx, y, cw, mouseX, mouseY, hoverAllowed, host);
        }
    }

    public static boolean clickBlock(Module module, double mx, double my, int rowX, int rowY,
                                     int rowW, int button, Host host, Drag drag) {
        return clickBlock(module.getSettings(), module.getKeybind(), mx, my, rowX, rowY, rowW,
            button, host, drag);
    }

    public static boolean clickBlock(List<Setting<?>> settings, Setting<?> trailer,
                                     double mx, double my, int rowX, int rowY,
                                     int rowW, int button, Host host, Drag drag) {
        int cx = blockContentX(rowX);
        int cw = blockContentWidth(rowW);
        int y = rowY + 2;
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
        if (trailer != null && isOver(mx, my, cx, y, cw, GuiTheme.SETTING_HEIGHT)) {
            click(trailer, mx, my, cx, y, cw, button, host, drag);
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
        if (InputUtil.isMiddle(button)) {
            setting.reset();
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
            return;
        }
        // Only the left and the right button act on a setting.
        if (!InputUtil.isLeft(button) && !InputUtil.isRight(button)) {
            return;
        }
        switch (setting) {
            case KeybindSetting k -> {
                host.startListening(k);
                return;
            }
            case BoolSetting b -> b.toggle();
            case NumberSetting n -> {
                if (InputUtil.isLeft(button) && overNumberValue(OfflineClient.MC.font, mx, my, n, x, y, width)) {
                    host.startEditing(n);
                    return;
                }
                n.beginSlider();
                drag.slider = n;
                n.setFromSlider(fraction(x, width, mx));
            }
            case EnumSetting<?> e -> e.cycle(InputUtil.isLeft(button));
            case ColorSetting c -> {
                if (InputUtil.isRight(button) || overRainbowChip(OfflineClient.MC.font, mx, my, x, y, width)) {
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
            // A left press waits to see whether it becomes a drag. Release decides.
            case RankSetting<?> r -> {
                int index = entryAt(r, my, y);
                if (index < 0) {
                    return;
                }
                if (InputUtil.isLeft(button)) {
                    drag.grab(r, index, my, entryTop(y, 0));
                    return;
                }
                r.toggle(index);
            }
            // The name row cycles the size like a choice. A left press on a cell paints the
            // opposite of what it lands on and a right press rubs out. Release saves the stroke.
            case GridSetting g -> {
                if (my < y + GuiTheme.SETTING_HEIGHT) {
                    g.cycleSize(InputUtil.isLeft(button));
                } else {
                    GridBox box = GridBox.of(g, x, y, width);
                    GridSetting.Cell cell = box.cellAt(mx, my);
                    if (cell == null) {
                        return;
                    }
                    boolean paint = InputUtil.isLeft(button) && !g.isOn(cell.column(), cell.row());
                    g.set(cell.column(), cell.row(), paint);
                    drag.paint(g, box, cell, paint);
                    return;
                }
            }
            // Whatever the action changes saves itself. The row holds nothing.
            case ActionSetting a -> {
                a.press();
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
        // A lone dot reads worse than an empty space.
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
