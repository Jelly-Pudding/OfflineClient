package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * A draggable column of module rows with a category header. Edges and
 * corners resize and a scrollbar shows when the rows do not all fit.
 */
public final class Panel {

    private static final int MIN_VIEW = 16;
    private static final int MIN_WIDTH = 64;
    private static final int MAX_WIDTH = 320;
    /** How close to an edge the mouse must be to grab it. */
    private static final int GRAB = 4;
    private static final int SCROLLBAR = 3;

    private final String title;
    private final List<ModuleRow> rows = new ArrayList<>();

    private int x;
    private int y;
    private int width = GuiTheme.PANEL_WIDTH;
    private boolean collapsed;

    private boolean dragging;
    private int dragOffsetX;
    private int dragOffsetY;

    // Which edges are being dragged right now.
    private boolean sizeLeft;
    private boolean sizeRight;
    private boolean sizeTop;
    private boolean sizeBottom;

    /** Zero means show everything that fits on screen. */
    private int viewHeight;
    private int scrollOffset;
    private boolean draggingScrollbar;
    private int scrollDragStartY;
    private int scrollDragStartOffset;
    private int screenHeight;

    public Panel(String title, List<Module> modules, ClickGuiScreen screen, int x, int y) {
        this.title = title;
        this.x = x;
        this.y = y;
        for (Module module : modules) {
            rows.add(new ModuleRow(module, screen));
        }
    }

    public String getTitle() {
        return title;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getWidth() {
        return width;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public int getViewHeight() {
        return viewHeight;
    }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public void setWidth(int width) {
        this.width = Math.clamp(width, MIN_WIDTH, MAX_WIDTH);
    }

    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
    }

    public void setViewHeight(int viewHeight) {
        this.viewHeight = viewHeight;
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    public void setScrollOffset(int scrollOffset) {
        this.scrollOffset = scrollOffset;
    }

    public List<ModuleRow> getRows() {
        return rows;
    }

    private int rowsHeight() {
        int height = 0;
        for (ModuleRow row : rows) {
            height += row.getHeight();
        }
        return height;
    }

    /**
     * How tall the visible row area is right now. Never taller than the
     * rows themselves or the space left on screen below the panel.
     */
    private int viewportHeight() {
        int full = rowsHeight();
        int limit = viewHeight > 0 ? viewHeight : full;
        if (screenHeight > 0) {
            int available = screenHeight - y - GuiTheme.HEADER_HEIGHT - 2;
            limit = Math.min(limit, available);
        }
        return Math.clamp(limit, Math.min(MIN_VIEW, full), full);
    }

    public int getContentHeight() {
        if (collapsed) {
            return GuiTheme.HEADER_HEIGHT;
        }
        return GuiTheme.HEADER_HEIGHT + viewportHeight() + 2;
    }

    /** The full rectangle including the grab margin around it. */
    public boolean isOver(double mx, double my) {
        return mx >= x - GRAB && mx < x + width + GRAB
            && my >= y - GRAB && my < y + getContentHeight() + GRAB;
    }

    /** True if the mouse is on the panel body itself. */
    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + getContentHeight();
    }

    /** Mouse wheel input. Scrolls the rows inside the panel. */
    public void scroll(int dy) {
        scrollOffset = clampScroll(scrollOffset - dy);
    }

    private int clampScroll(int value) {
        return Math.clamp(value, 0, Math.max(0, rowsHeight() - viewportHeight()));
    }

    // The grab bands sit on the border and just outside it. Clicks inside
    // the panel body never resize.
    private boolean nearLeft(double mx, double my) {
        return mx >= x - GRAB && mx <= x + 1 && my >= y - GRAB && my < y + getContentHeight() + GRAB;
    }

    private boolean nearRight(double mx, double my) {
        return mx >= x + width - 1 && mx <= x + width + GRAB
            && my >= y - GRAB && my < y + getContentHeight() + GRAB;
    }

    private boolean nearTop(double mx, double my) {
        return !collapsed && my >= y - GRAB && my <= y + 1 && mx >= x - GRAB && mx < x + width + GRAB;
    }

    private boolean nearBottom(double mx, double my) {
        int bottom = y + getContentHeight();
        return !collapsed && my >= bottom - 1 && my <= bottom + GRAB
            && mx >= x - GRAB && mx < x + width + GRAB;
    }

    public boolean isDragging() {
        return dragging;
    }

    private boolean scrollable() {
        return !collapsed && rowsHeight() > viewportHeight();
    }

    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        screenHeight = context.guiHeight();

        if (dragging) {
            x = mouseX - dragOffsetX;
            y = mouseY - dragOffsetY;
            x = Math.clamp(x, 0, Math.max(0, context.guiWidth() - width));
            y = Math.clamp(y, 0, Math.max(0, context.guiHeight() - GuiTheme.HEADER_HEIGHT));
        }
        applyResize(mouseX, mouseY);
        if (draggingScrollbar) {
            int track = viewportHeight();
            int total = rowsHeight();
            int delta = mouseY - scrollDragStartY;
            scrollOffset = clampScroll(scrollDragStartOffset + delta * total / Math.max(1, track));
        }
        scrollOffset = clampScroll(scrollOffset);

        Font font = OfflineClient.MC.font;
        int w = width;
        int contentH = getContentHeight();

        boolean hotL = sizeLeft || nearLeft(mouseX, mouseY);
        boolean hotR = sizeRight || nearRight(mouseX, mouseY);
        boolean hotT = sizeTop || nearTop(mouseX, mouseY);
        boolean hotB = sizeBottom || nearBottom(mouseX, mouseY);
        int frame = GuiTheme.OUTLINE;
        int hot = GuiTheme.accent();
        context.fill(x - 1, y - 1, x + w + 1, y + contentH + 1, frame);
        context.guiRenderState.up();
        if (hotL) {
            context.fill(x - 1, y - 1, x, y + contentH + 1, hot);
        }
        if (hotR) {
            context.fill(x + w, y - 1, x + w + 1, y + contentH + 1, hot);
        }
        if (hotT) {
            context.fill(x - 1, y - 1, x + w + 1, y, hot);
        }
        if (hotB) {
            context.fill(x - 1, y + contentH, x + w + 1, y + contentH + 1, hot);
        }
        context.guiRenderState.up();

        // Header: dark bar with accent underline and title.
        context.fill(x, y, x + w, y + GuiTheme.HEADER_HEIGHT, GuiTheme.BG_PANEL);
        context.guiRenderState.up();
        context.fill(x, y + GuiTheme.HEADER_HEIGHT - 2, x + w, y + GuiTheme.HEADER_HEIGHT,
            GuiTheme.accent());
        // The title gets clipped when the panel is narrower than the text.
        context.enableScissor(x, y, x + w - 14, y + GuiTheme.HEADER_HEIGHT);
        RenderUtil.gradientText(context, font, title, x + 5, y + 5,
            GuiTheme.accent(), GuiTheme.accent(12));
        context.disableScissor();
        String marker = collapsed ? "+" : "-";
        context.text(font, marker, x + w - 9, y + 5, GuiTheme.TEXT_DIM, false);

        if (collapsed) {
            return;
        }

        int viewTop = y + GuiTheme.HEADER_HEIGHT;
        int viewH = viewportHeight();
        int rowW = scrollable() ? w - SCROLLBAR - 1 : w;

        // Rows outside the viewport should not show hover effects. Sliders
        // being dragged still get the real mouse position.
        boolean mouseInView = mouseX >= x && mouseX < x + rowW
            && mouseY >= viewTop && mouseY < viewTop + viewH;

        context.fill(x, viewTop, x + w, viewTop + viewH + 2, GuiTheme.BG_PANEL);
        context.guiRenderState.up();
        context.enableScissor(x, viewTop, x + rowW, viewTop + viewH);
        int rowY = viewTop - scrollOffset;
        for (ModuleRow row : rows) {
            row.render(context, x, rowY, rowW, mouseX, mouseY, mouseInView);
            rowY += row.getHeight();
        }
        context.disableScissor();

        // Scrollbar. A thin track with a thumb sized to the visible share.
        if (scrollable()) {
            int trackX = x + w - SCROLLBAR;
            context.fill(trackX, viewTop, trackX + SCROLLBAR, viewTop + viewH, GuiTheme.BG_ROW);
            int total = rowsHeight();
            int thumbH = Math.max(8, viewH * viewH / total);
            int thumbY = viewTop + (viewH - thumbH) * scrollOffset / Math.max(1, total - viewH);
            boolean overBar = mouseX >= trackX - 2 && mouseX < x + w
                && mouseY >= viewTop && mouseY < viewTop + viewH;
            context.guiRenderState.up();
            context.fill(trackX, thumbY, trackX + SCROLLBAR, thumbY + thumbH,
                draggingScrollbar || overBar ? GuiTheme.accent() : GuiTheme.TEXT_DIM);
        }
    }

    private void applyResize(int mouseX, int mouseY) {
        if (sizeRight) {
            setWidth(mouseX - x);
        }
        if (sizeLeft) {
            int right = x + width;
            int newX = Math.clamp(mouseX, right - MAX_WIDTH, right - MIN_WIDTH);
            x = newX;
            width = right - newX;
        }
        if (sizeBottom) {
            viewHeight = Math.clamp(mouseY - y - GuiTheme.HEADER_HEIGHT - 2, MIN_VIEW, rowsHeight());
        }
        if (sizeTop) {
            int bottom = y + GuiTheme.HEADER_HEIGHT + viewportHeight() + 2;
            int minY = bottom - GuiTheme.HEADER_HEIGHT - 2 - rowsHeight();
            int maxY = bottom - GuiTheme.HEADER_HEIGHT - 2 - MIN_VIEW;
            int newY = Math.clamp(mouseY, Math.max(0, minY), maxY);
            viewHeight = bottom - newY - GuiTheme.HEADER_HEIGHT - 2;
            y = newY;
        }
    }

    /** Returns true if the click was handled by this panel. */
    public boolean mouseClicked(double rawMx, double rawMy, int button) {
        // Whole pixels to match the hover highlight.
        int mx = (int) rawMx;
        int my = (int) rawMy;
        int w = width;

        // Edge and corner resizing is checked before the header.
        boolean l = nearLeft(mx, my);
        boolean r = nearRight(mx, my);
        boolean t = nearTop(mx, my);
        boolean b = nearBottom(mx, my);
        if (l || r || t || b) {
            if (button == 0) {
                sizeLeft = l;
                sizeRight = r;
                sizeTop = t;
                sizeBottom = b;
            } else if (button == 1) {
                // Right click on an edge resets that direction.
                if (l || r) {
                    width = GuiTheme.PANEL_WIDTH;
                }
                if (t || b) {
                    viewHeight = 0;
                    scrollOffset = 0;
                }
            }
            return true;
        }

        if (mx >= x && mx < x + w && my >= y && my < y + GuiTheme.HEADER_HEIGHT) {
            // The marker on the right collapses with a left click. Anywhere
            // else on the header drags. Right click collapses too.
            if (button == 0 && mx >= x + w - 14) {
                collapsed = !collapsed;
            } else if (button == 0) {
                dragging = true;
                dragOffsetX = (int) mx - x;
                dragOffsetY = (int) my - y;
            } else if (button == 1) {
                collapsed = !collapsed;
            }
            return true;
        }

        if (collapsed) {
            return false;
        }

        int viewTop = y + GuiTheme.HEADER_HEIGHT;
        int viewH = viewportHeight();
        if (mx < x || mx >= x + w || my < viewTop || my >= viewTop + viewH) {
            return contains(mx, my);
        }

        // Scrollbar drag.
        if (scrollable() && mx >= x + w - SCROLLBAR - 2) {
            draggingScrollbar = true;
            scrollDragStartY = (int) my;
            scrollDragStartOffset = scrollOffset;
            return true;
        }

        for (ModuleRow row : rows) {
            if (row.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        // Clicks on the panel body are consumed.
        return true;
    }

    public void mouseReleased() {
        dragging = false;
        draggingScrollbar = false;
        sizeLeft = false;
        sizeRight = false;
        sizeTop = false;
        sizeBottom = false;
        for (ModuleRow row : rows) {
            row.mouseReleased();
        }
    }
}
