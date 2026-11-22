package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.function.IntFunction;

final class WindowParts {

    static final int TITLE_HEIGHT = 22;
    static final int TITLE_INSET = 10;
    static final int TITLE_GAP = 4;
    static final int FOOT_HEIGHT = 28;
    private static final int FOOT_LINES = 2;
    static final int SIDEBAR_WIDTH = 108;
    static final int MARGIN = 6;

    private static final int MAX_WIDTH = 640;
    private static final int MAX_HEIGHT = 400;
    private static final int MIN_WIDTH = 300;
    private static final int MIN_HEIGHT = 180;

    private static final int TAB_ROW = 18;
    private static final int TAB_ROW_MIN = 12;
    private static final int TAB_TEXT_X = 8;

    private WindowParts() {
    }

    static int width(int viewWidth) {
        return Math.clamp(viewWidth - 20, MIN_WIDTH, MAX_WIDTH);
    }

    static int height(int viewHeight) {
        return Math.clamp(viewHeight - 20, MIN_HEIGHT, MAX_HEIGHT);
    }

    static int tabWidth() {
        return SIDEBAR_WIDTH - MARGIN - 4;
    }

    static void frame(GuiGraphicsExtractor context, int x, int y, int w, int h) {
        RenderUtil.roundedBorderedRect(context, x, y, x + w, y + h, GuiTheme.CORNER + 2, GuiTheme.bgWindow(),
            GuiTheme.edge());
        context.guiRenderState.up();
        RenderUtil.roundedRect(context, x + 1, y + 1, x + w - 1, y + TITLE_HEIGHT, GuiTheme.CORNER + 1,
            GuiTheme.bgHeader(), true, false);
        context.guiRenderState.up();
        context.fill(x + 1, y + TITLE_HEIGHT - 1, x + w - 1, y + TITLE_HEIGHT + 1, GuiTheme.accent());
    }

    static int titleY(int y) {
        return GuiTheme.textY(y, TITLE_HEIGHT - 2);
    }

    static void title(GuiGraphicsExtractor context, Font font, int x, int y, int w, String name, String under,
                      String right) {
        int textY = titleY(y);
        context.text(font, name, x + TITLE_INSET, textY, GuiTheme.accentText(), false);
        context.text(font, under, x + TITLE_INSET + font.width(name) + TITLE_GAP, textY, GuiTheme.textFaint(), false);
        context.text(font, right, x + w - TITLE_INSET - font.width(right), textY, GuiTheme.textDim(), false);
    }

    static String place() {
        String address = ServerInfo.address();
        return address == null ? "single player" : address;
    }

    static void footer(GuiGraphicsExtractor context, Font font, int x, int y, int w, int h,
                       String text, int color) {
        int left = x + 1;
        int top = y + h - FOOT_HEIGHT - 1;
        RenderUtil.roundedRect(context, left, top, left + w - 2, top + FOOT_HEIGHT, GuiTheme.CORNER + 1,
            GuiTheme.bgHeader(), false, true);
        context.fill(left, top, left + w - 2, top + 1, GuiTheme.edge());
        context.guiRenderState.up();
        int room = w - 20;
        int pitch = font.lineHeight + 1;
        List<String> lines = RenderUtil.wrap(font, text, room);
        int shown = Math.min(lines.size(), FOOT_LINES);
        int lineY = GuiTheme.textY(top + 1, FOOT_HEIGHT - (shown - 1) * pitch);
        for (int i = 0; i < shown; i++) {
            String line = i < shown - 1 ? lines.get(i) : String.join(" ", lines.subList(i, lines.size()));
            context.text(font, SettingWidget.trimEnd(font, line, room), left + 9, lineY, color, false);
            lineY += pitch;
        }
    }

    static void tabs(GuiGraphicsExtractor context, Font font, int x, int top, int w, int height,
                     List<String> names, IntFunction<String> tally, int chosen, int mouseX, int mouseY) {
        int pitch = tabPitch(height, names.size());
        int h = pitch - 2;
        int y = top;
        context.enableScissor(x, top, x + w, top + height);
        for (int i = 0; i < names.size(); i++) {
            boolean selected = i == chosen;
            boolean hovered = SettingWidget.isOver(mouseX, mouseY, x, y, w, h);
            if (selected || hovered) {
                RenderUtil.roundedRect(context, x, y, x + w, y + h, GuiTheme.CORNER,
                    selected ? GuiTheme.accentOn(GuiTheme.bgRow(), 0.35f) : GuiTheme.bgRowHover());
                context.guiRenderState.up();
            }
            if (selected) {
                RenderUtil.roundedRect(context, x, y + 2, x + 2, y + h - 2, 1, GuiTheme.accent());
            }
            int ty = GuiTheme.textY(y, h);
            String count = tally.apply(i);
            int tallyX = x + w - MARGIN - font.width(count);
            context.text(font, SettingWidget.trimEnd(font, names.get(i), tallyX - x - 12), x + TAB_TEXT_X, ty,
                selected || hovered ? GuiTheme.text() : GuiTheme.textDim(), false);
            context.text(font, count, tallyX, ty, selected ? GuiTheme.textDim() : GuiTheme.textFaint(), false);
            y += pitch;
        }
        context.disableScissor();
    }

    static int tabAt(double mx, double my, int x, int top, int w, int height, int count) {
        int pitch = tabPitch(height, count);
        int y = top;
        for (int i = 0; i < count && y < top + height; i++) {
            if (SettingWidget.isOver(mx, my, x, y, w, pitch - 2)) {
                return i;
            }
            y += pitch;
        }
        return -1;
    }

    private static int tabPitch(int height, int count) {
        return Math.clamp(height / Math.max(1, count), TAB_ROW_MIN, TAB_ROW);
    }
}
