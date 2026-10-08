package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

// A scrolled list of rows two lines tall with buttons at the end of each. The first line says
// what the row is and the second is written fainter under it.
final class RowList<T> {

    // A button at the end of a row. One that warns is drawn in red.
    interface Button {
        String label();

        String help();

        boolean warns();
    }

    // One row as drawn. The buttons read from left to right and the help shows in the footer
    // whilst the pointer rests on the row.
    record Row<T>(T item, String text, int color, String detail, List<? extends Button> buttons, String help) {
    }

    // Where a button landed this frame and the row it acts on.
    private record Spot<T>(T item, Button button, int x, int y, int w) {
    }

    static final int ROW_HEIGHT = 24;
    private static final int LINE_GAP = 10;
    private static final int BUTTON_HEIGHT = 12;
    private static final int BUTTON_PAD = 4;
    private static final int BUTTON_GAP = 3;

    private final ScrollBar scrollBar = new ScrollBar();
    private final List<Spot<T>> spots = new ArrayList<>();
    private List<Row<T>> rows = List.of();
    // Where the list drew last. Clicks and the wheel are measured against it.
    private int x;
    private int top;
    private int w;
    private int h;

    void show(List<Row<T>> shown) {
        rows = List.copyOf(shown);
        scrollBar.setOffset(ScrollBar.clamp(scrollBar.getOffset(), totalHeight(), h));
    }

    int size() {
        return rows.size();
    }

    void toTop() {
        scrollBar.setOffset(0);
    }

    private int totalHeight() {
        return rows.size() * ROW_HEIGHT;
    }

    // The empty text shows when there are no rows.
    void render(GuiGraphicsExtractor context, Font font, int x, int top, int w, int h, int mouseX, int mouseY,
                Footer footer, String empty) {
        this.x = x;
        this.top = top;
        this.w = w;
        this.h = h;
        int total = totalHeight();
        int rowW = ScrollBar.rowWidth(w, total, h);
        scrollBar.update(mouseY, total, h);

        RenderUtil.roundedRect(context, x, top, x + w, top + h, GuiTheme.CORNER, GuiTheme.bgPanel());
        context.guiRenderState.up();

        spots.clear();
        boolean inView = SettingWidget.isOver(mouseX, mouseY, x, top, rowW, h);
        context.enableScissor(x, top, x + rowW, top + h);
        int rowY = top - scrollBar.getOffset();
        for (Row<T> row : rows) {
            if (rowY + ROW_HEIGHT > top && rowY < top + h) {
                renderRow(context, font, row, rowY, rowW, inView, mouseX, mouseY, footer);
            }
            rowY += ROW_HEIGHT;
        }
        if (rows.isEmpty()) {
            context.text(font, empty, x + 8, GuiTheme.textY(top, ROW_HEIGHT), GuiTheme.textFaint(), false);
        }
        context.disableScissor();

        if (total > h) {
            int trackX = ScrollBar.trackX(x, w);
            scrollBar.render(context, trackX, top, h, total, ScrollBar.isOverTrack(mouseX, mouseY, trackX, top, h));
        }
    }

    // The two lines with the buttons laid out from the right edge.
    private void renderRow(GuiGraphicsExtractor context, Font font, Row<T> row, int y, int rowW, boolean inView,
                           int mouseX, int mouseY, Footer footer) {
        boolean hovered = inView && SettingWidget.isOver(mouseX, mouseY, x, y, rowW, ROW_HEIGHT);
        RenderUtil.roundedRect(context, x + 2, y + 1, x + rowW - 2, y + ROW_HEIGHT - 1, GuiTheme.CORNER,
            hovered ? GuiTheme.bgRowHover() : GuiTheme.bgRow());
        context.guiRenderState.up();

        int left = x + rowW - 4;
        int buttonY = y + (ROW_HEIGHT - BUTTON_HEIGHT) / 2;
        for (Button button : row.buttons().reversed()) {
            int buttonW = font.width(button.label()) + BUTTON_PAD * 2;
            left -= buttonW;
            boolean over = inView && SettingWidget.isOver(mouseX, mouseY, left, buttonY, buttonW, BUTTON_HEIGHT);
            if (button.warns()) {
                warning(context, font, left, buttonY, buttonW, BUTTON_HEIGHT, button.label());
            } else {
                GuiTheme.button(context, font, left, buttonY, buttonW, BUTTON_HEIGHT, button.label(), over, hovered);
            }
            spots.add(new Spot<>(row.item(), button, left, buttonY, buttonW));
            if (over) {
                footer.show(button.help(), button.warns() ? GuiTheme.RED_TEXT : GuiTheme.text());
            }
            left -= BUTTON_GAP;
        }

        int textX = x + 8;
        int room = left - 4 - textX;
        context.text(font, SettingWidget.trimEnd(font, row.text(), room), textX, y + 4, row.color(), false);
        context.text(font, SettingWidget.trimEnd(font, row.detail(), room), textX, y + 4 + LINE_GAP,
            GuiTheme.textFaint(), false);
        if (hovered) {
            footer.show(row.help(), GuiTheme.text());
        }
    }

    // A button that throws work away once pressed again. It has a red edge and red writing.
    static void warning(GuiGraphicsExtractor context, Font font, int x, int y, int w, int h, String label) {
        RenderUtil.roundedBorderedRect(context, x, y, x + w, y + h, GuiTheme.CORNER, GuiTheme.bgPanel(), GuiTheme.RED);
        context.guiRenderState.up();
        context.centeredText(font, label, x + w / 2, GuiTheme.textY(y, h), GuiTheme.RED_TEXT);
    }

    // True when the click fell on the list. A row or one of its buttons goes to the handler
    // and a null button stands for the row itself.
    boolean click(double mx, double my, BiConsumer<T, Button> pressed) {
        if (!SettingWidget.isOver(mx, my, x, top, w, h)) {
            return false;
        }
        int trackX = ScrollBar.trackX(x, w);
        if (totalHeight() > h && ScrollBar.isOverTrack(mx, my, trackX, top, h)) {
            scrollBar.beginDrag((int) my);
            return true;
        }
        for (Spot<T> spot : spots) {
            if (SettingWidget.isOver(mx, my, spot.x(), spot.y(), spot.w(), BUTTON_HEIGHT)) {
                pressed.accept(spot.item(), spot.button());
                return true;
            }
        }
        int index = (int) Math.floor((my - top + scrollBar.getOffset()) / ROW_HEIGHT);
        boolean onRow = mx < x + ScrollBar.rowWidth(w, totalHeight(), h);
        if (onRow && index >= 0 && index < rows.size()) {
            pressed.accept(rows.get(index).item(), null);
        }
        return true;
    }

    // Nothing scrolls whilst the thumb is held. The rows would slide out from under it.
    boolean scroll(double mx, double my, double scrollY) {
        if (OfflineClient.MC.mouseHandler.isLeftPressed() || !SettingWidget.isOver(mx, my, x, top, w, h)) {
            return false;
        }
        scrollBar.scroll(ScrollBar.wheelDelta(scrollY, totalHeight(), h), totalHeight(), h);
        return true;
    }

    void release() {
        scrollBar.release();
    }
}
