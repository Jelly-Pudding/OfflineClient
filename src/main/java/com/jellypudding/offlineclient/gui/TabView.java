package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

// What a tab opens. A box under the tabs holding one row per entry. Drag an
// edge to resize it.
public final class TabView {

    // What the box is looking at. The client owns the data and this reads it.
    public interface Source {
        List<String> entries();

        // Shown when there is nothing to list.
        String emptyText();

        // What a left click does. Empty means a click does nothing.
        default String actionName() {
            return "";
        }

        default void activate(String entry) {
        }

        default void drop(String entry) {
        }
    }

    // A multiplication sign. The closest thing to a cross the font has.
    private static final String DROP = "×";
    private static final int DROP_ZONE = 14;
    private static final int EMPTY_HEIGHT = 18;
    private static final int GRAB = 4;
    private static final int PAD = 2;
    private static final int MIN_WIDTH = 120;
    private static final int MAX_WIDTH = 460;
    private static final int MIN_HEIGHT = 40;
    private static final int DEFAULT_WIDTH = 200;
    private static final int DEFAULT_HEIGHT = 150;

    // A listing can reach the disk. Often enough to feel live is plenty.
    private static final long REFRESH_MS = 500;

    private final Source source;
    private final SettingWidget.Host host;
    private final ScrollBar scrollBar = new ScrollBar();

    private int width = DEFAULT_WIDTH;
    private int height = DEFAULT_HEIGHT;

    private boolean sizeLeft;
    private boolean sizeRight;
    private boolean sizeBottom;

    private List<String> entries = List.of();
    private long read;

    // Where the box landed this frame. The width and height above are what
    // the user asked for and these are what the window had room for.
    private int left;
    private int top;
    private int wide = MIN_WIDTH;
    private int drawn = MIN_HEIGHT;

    public TabView(Source source, SettingWidget.Host host) {
        this.source = source;
        this.host = host;
        refresh();
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void setWidth(int value) {
        width = Math.clamp(value, MIN_WIDTH, MAX_WIDTH);
    }

    public void setHeight(int value) {
        height = Math.max(MIN_HEIGHT, value);
    }

    private void refresh() {
        entries = source.entries();
        read = System.currentTimeMillis();
    }

    private List<String> entries() {
        if (System.currentTimeMillis() - read > REFRESH_MS) {
            refresh();
        }
        return entries;
    }

    private int contentHeight() {
        int count = entries().size();
        return count == 0 ? EMPTY_HEIGHT : count * GuiTheme.ROW_HEIGHT;
    }

    // What the box is allowed to draw at right now. The size the user chose
    // is kept whole even when the window is too small to show it.
    private int drawHeight(int viewH, int under) {
        return Math.clamp(height, MIN_HEIGHT, Math.max(MIN_HEIGHT, viewH - under - 6));
    }

    private int drawWidth(int viewW) {
        return Math.clamp(width, MIN_WIDTH, Math.max(MIN_WIDTH, viewW - 8));
    }

    private int viewHeight() {
        return Math.max(0, drawn - PAD * 2);
    }

    private int rowRoom() {
        return wide - PAD * 2;
    }

    public boolean isOver(double mx, double my) {
        return mx >= left - GRAB && mx < left + wide + GRAB
            && my >= top - GRAB && my < top + drawn + GRAB;
    }

    public void wheel(double amount) {
        int full = contentHeight();
        int view = viewHeight();
        scrollBar.scroll(ScrollBar.wheelDelta(amount, full, view), full, view);
    }

    public void release() {
        sizeLeft = false;
        sizeRight = false;
        sizeBottom = false;
        scrollBar.release();
    }

    public void render(GuiGraphicsExtractor context, int viewW, int viewH, int under,
                       int mouseX, int mouseY) {
        applyResize(viewW, viewH, under, mouseX, mouseY);
        wide = drawWidth(viewW);
        drawn = drawHeight(viewH, under);
        left = (viewW - wide) / 2;
        top = under;

        int full = contentHeight();
        int view = viewHeight();
        scrollBar.update(mouseY, full, view);

        RenderUtil.shadow(context, left, top, left + wide, top + drawn, 3);
        context.guiRenderState.up();
        RenderUtil.roundedBorderedRect(context, left, top, left + wide, top + drawn,
            GuiTheme.CORNER + 1, GuiTheme.bgPanel(), GuiTheme.outline());
        context.guiRenderState.up();
        renderGrabBands(context, mouseX, mouseY);

        int rowW = ScrollBar.rowWidth(rowRoom(), full, view);
        int rowX = left + PAD;
        int viewTop = top + PAD;
        context.enableScissor(rowX, viewTop, rowX + rowW, viewTop + view);
        renderRows(context, rowX, viewTop, view, rowW, mouseX, mouseY);
        context.disableScissor();

        if (full > view) {
            int trackX = ScrollBar.trackX(rowX, rowRoom());
            scrollBar.render(context, trackX, viewTop, view, full,
                ScrollBar.isOverTrack(mouseX, mouseY, trackX, viewTop, view));
        }
    }

    private void renderRows(GuiGraphicsExtractor context, int rowX, int viewTop, int view,
                            int rowW, int mouseX, int mouseY) {
        Font font = OfflineClient.MC.font;
        List<String> rows = entries();
        if (rows.isEmpty()) {
            context.text(font, source.emptyText(), rowX + GuiTheme.PAD,
                GuiTheme.textY(viewTop, EMPTY_HEIGHT), GuiTheme.textFaint(), false);
            return;
        }
        boolean inView = mouseX >= rowX && mouseX < rowX + rowW
            && mouseY >= viewTop && mouseY < viewTop + view;
        int rowY = viewTop - scrollBar.getOffset();
        for (String entry : rows) {
            int h = GuiTheme.ROW_HEIGHT;
            if (rowY + h > viewTop && rowY < viewTop + view) {
                boolean hovered = inView && SettingWidget.isOver(mouseX, mouseY, rowX, rowY, rowW, h);
                context.fill(rowX, rowY, rowX + rowW, rowY + h - 1,
                    hovered ? GuiTheme.bgRowHover() : GuiTheme.bgRow());
                context.fill(rowX, rowY + h - 1, rowX + rowW, rowY + h, GuiTheme.RULE);
                context.guiRenderState.up();

                int room = rowW - GuiTheme.PAD - DROP_ZONE;
                context.text(font, SettingWidget.trimEnd(font, entry, room),
                    rowX + GuiTheme.PAD, GuiTheme.textY(rowY, h - 1),
                    hovered ? GuiTheme.text() : GuiTheme.textDim(), false);

                boolean overDrop = hovered && mouseX >= rowX + rowW - DROP_ZONE;
                context.text(font, DROP, rowX + rowW - DROP_ZONE
                        + (DROP_ZONE - font.width(DROP)) / 2, GuiTheme.textY(rowY, h - 1),
                    overDrop ? ColorUtil.lerp(GuiTheme.text(), 0xFFFF6B6B, 0.7f)
                        : GuiTheme.textFaint(), false);

                if (hovered) {
                    host.setTooltip(overDrop ? "Remove " + entry : hoverHelp(entry));
                }
            }
            rowY += h;
        }
    }

    private String hoverHelp(String entry) {
        String action = source.actionName();
        return action.isEmpty() ? entry : action + " " + entry;
    }

    private void renderGrabBands(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int hot = GuiTheme.accent();
        if (sizeLeft || sizeRight || nearLeft(mouseX, mouseY) || nearRight(mouseX, mouseY)) {
            context.fill(left - 1, top, left, top + drawn, hot);
            context.fill(left + wide, top, left + wide + 1, top + drawn, hot);
        }
        if (sizeBottom || nearBottom(mouseX, mouseY)) {
            context.fill(left, top + drawn, left + wide, top + drawn + 1, hot);
        }
        context.guiRenderState.up();
    }

    // The bands sit outside the box. Reaching inwards would cover the
    // scrollbar and the first pixels of every row.
    private boolean nearLeft(double mx, double my) {
        return inRow(my) && mx >= left - GRAB && mx <= left + 1;
    }

    private boolean nearRight(double mx, double my) {
        return inRow(my) && mx >= left + wide - 1 && mx <= left + wide + GRAB;
    }

    private boolean inRow(double my) {
        return my >= top && my <= top + drawn;
    }

    private boolean nearBottom(double mx, double my) {
        return mx >= left - GRAB && mx <= left + wide + GRAB
            && Math.abs(my - (top + drawn)) <= GRAB;
    }

    // The box stays centred. The grabbed side widens it both ways at once
    // and pulling past the middle pins it rather than turning it inside out.
    private void applyResize(int viewW, int viewH, int under, int mouseX, int mouseY) {
        int middle = viewW / 2;
        if (sizeLeft || sizeRight) {
            int half = sizeRight ? mouseX - middle : middle - mouseX;
            setWidth(Math.max(0, half) * 2);
        }
        if (sizeBottom) {
            setHeight(Math.clamp(mouseY - under, MIN_HEIGHT,
                Math.max(MIN_HEIGHT, viewH - under - 6)));
        }
    }

    // True when the click belonged to the box.
    public boolean mouseClicked(double mx, double my, int button) {
        boolean leftEdge = nearLeft(mx, my);
        boolean rightEdge = nearRight(mx, my);
        boolean bottom = nearBottom(mx, my);
        if (leftEdge || rightEdge || bottom) {
            if (InputUtil.isLeft(button)) {
                sizeLeft = leftEdge;
                sizeRight = rightEdge && !leftEdge;
                sizeBottom = bottom;
            } else if (InputUtil.isRight(button)) {
                width = DEFAULT_WIDTH;
                height = DEFAULT_HEIGHT;
            }
            return true;
        }
        if (mx < left || mx >= left + wide || my < top || my >= top + drawn) {
            return false;
        }

        int full = contentHeight();
        int view = viewHeight();
        int rowX = left + PAD;
        int viewTop = top + PAD;
        int trackX = ScrollBar.trackX(rowX, rowRoom());
        if (full > view && ScrollBar.isOverTrack(mx, my, trackX, viewTop, view)) {
            scrollBar.beginDrag((int) my);
            return true;
        }
        // The pad above and below the strip belongs to no row.
        if (my < viewTop || my >= viewTop + view || !InputUtil.isLeft(button)) {
            return true;
        }

        int rowW = ScrollBar.rowWidth(rowRoom(), full, view);
        int rowY = viewTop - scrollBar.getOffset();
        for (String entry : entries()) {
            int h = GuiTheme.ROW_HEIGHT;
            if (SettingWidget.isOver(mx, my, rowX, rowY, rowW, h)) {
                if (mx >= rowX + rowW - DROP_ZONE) {
                    source.drop(entry);
                } else if (!source.actionName().isEmpty()) {
                    source.activate(entry);
                }
                refresh();
                return true;
            }
            rowY += h;
        }
        return true;
    }
}
