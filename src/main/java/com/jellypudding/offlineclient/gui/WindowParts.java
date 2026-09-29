package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.function.IntFunction;

// The pieces of a centred window screen. The ClickGUI window and the finds list are both
// built from them and look alike.
final class WindowParts {

    static final int TITLE_HEIGHT = 22;
    static final int FOOT_HEIGHT = 18;
    static final int SIDEBAR_WIDTH = 108;
    static final int MARGIN = 6;

    private static final int MAX_WIDTH = 640;
    private static final int MAX_HEIGHT = 400;
    private static final int MIN_WIDTH = 300;
    private static final int MIN_HEIGHT = 180;

    // Side tabs tighten up on a short window and every tab stays in sight.
    private static final int TAB_ROW = 18;
    private static final int TAB_ROW_MIN = 12;
    private static final int TAB_TEXT_X = 8;

    private WindowParts() {
    }

    // How wide a window draws in a view of the given width.
    static int width(int viewWidth) {
        return Math.clamp(viewWidth - 20, MIN_WIDTH, MAX_WIDTH);
    }

    static int height(int viewHeight) {
        return Math.clamp(viewHeight - 20, MIN_HEIGHT, MAX_HEIGHT);
    }

    // How wide the tabs down the side are inside the sidebar.
    static int tabWidth() {
        return SIDEBAR_WIDTH - MARGIN - 4;
    }

    // The window with its title bar and the accent line under it. The caller writes the title.
    static void frame(GuiGraphicsExtractor context, int x, int y, int w, int h) {
        RenderUtil.roundedBorderedRect(context, x, y, x + w, y + h, GuiTheme.CORNER + 2, GuiTheme.bgWindow(),
            GuiTheme.edge());
        context.guiRenderState.up();
        RenderUtil.roundedRect(context, x + 1, y + 1, x + w - 1, y + TITLE_HEIGHT, GuiTheme.CORNER + 1,
            GuiTheme.bgHeader(), true, false);
        context.guiRenderState.up();
        context.fill(x + 1, y + TITLE_HEIGHT - 1, x + w - 1, y + TITLE_HEIGHT + 1, GuiTheme.accent());
    }

    // Where the title text of a window sits.
    static int titleY(int y) {
        return GuiTheme.textY(y, TITLE_HEIGHT - 2);
    }

    // The bar along the foot of a window with one line of text in it.
    static void footer(GuiGraphicsExtractor context, Font font, int x, int y, int w, int h,
                       String text, int color) {
        int left = x + 1;
        int top = y + h - FOOT_HEIGHT - 1;
        RenderUtil.roundedRect(context, left, top, left + w - 2, top + FOOT_HEIGHT, GuiTheme.CORNER + 1,
            GuiTheme.bgHeader(), false, true);
        context.fill(left, top, left + w - 2, top + 1, GuiTheme.edge());
        context.guiRenderState.up();
        context.text(font, SettingWidget.trimEnd(font, text, w - 20), left + 9,
            GuiTheme.textY(top + 1, FOOT_HEIGHT), color, false);
    }

    // Tabs down the side of a window with a tally on each. The chosen one is tinted with a
    // bar of the accent beside it.
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

    // The tab under the pointer or minus one.
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
