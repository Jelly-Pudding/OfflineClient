package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

// A draggable column of module rows under a category header.
public final class Panel {

    private static final int MIN_VIEW = 16;
    private static final int MIN_WIDTH = 64;
    private static final int MAX_WIDTH = 320;
    private static final int GRAB = 4;
    private static final int MARKER_ZONE = 14;

    private final String title;
    private final List<ModuleRow> rows = new ArrayList<>();
    private final ScrollBar scrollBar = new ScrollBar();

    private int x;
    private int y;
    private int width = GuiTheme.PANEL_WIDTH;
    private boolean collapsed;

    private boolean dragging;
    private int dragOffsetX;
    private int dragOffsetY;

    private boolean sizeLeft;
    private boolean sizeRight;
    private boolean sizeTop;
    private boolean sizeBottom;

    // Zero means show everything that fits on screen.
    private int viewHeight;
    private int screenHeight;

    public Panel(String title, List<Module> modules, SettingWidget.Host host, int x, int y) {
        this.title = title;
        this.x = x;
        this.y = y;
        for (Module module : modules) {
            rows.add(new ModuleRow(module, host));
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
        this.viewHeight = Math.max(0, viewHeight);
    }

    public int getScrollOffset() {
        return scrollBar.getOffset();
    }

    public void setScrollOffset(int scrollOffset) {
        scrollBar.setOffset(scrollOffset);
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

    // Never taller than the rows or the space left below the panel.
    private int viewportHeight() {
        int full = rowsHeight();
        int limit = viewHeight > 0 ? viewHeight : full;
        if (screenHeight > 0) {
            int available = screenHeight - y - GuiTheme.HEADER_HEIGHT - 2;
            limit = Math.min(limit, available);
        }
        return Math.clamp(limit, Math.min(MIN_VIEW, full), full);
    }

    private int minView() {
        return Math.min(MIN_VIEW, rowsHeight());
    }

    public int getContentHeight() {
        if (collapsed) {
            return GuiTheme.HEADER_HEIGHT;
        }
        return GuiTheme.HEADER_HEIGHT + viewportHeight() + 2;
    }

    // The full rectangle including the grab margin around it.
    public boolean isOver(double mx, double my) {
        return mx >= x - GRAB && mx < x + width + GRAB
            && my >= y - GRAB && my < y + getContentHeight() + GRAB;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + getContentHeight();
    }

    public void scroll(int dy) {
        scrollBar.scroll(dy, rowsHeight(), viewportHeight());
    }

    // The grab bands sit on the border and just outside it.
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
            x = Math.clamp(mouseX - dragOffsetX, 0, Math.max(0, context.guiWidth() - width));
            y = Math.clamp(mouseY - dragOffsetY, 0,
                Math.max(0, context.guiHeight() - GuiTheme.HEADER_HEIGHT));
        }
        applyResize(mouseX, mouseY);
        scrollBar.update(mouseY, rowsHeight(), viewportHeight());

        int contentH = getContentHeight();
        RenderUtil.shadow(context, x - 1, y - 1, x + width + 1, y + contentH + 1, 3);
        context.guiRenderState.up();
        RenderUtil.roundedRect(context, x - 1, y - 1, x + width + 1, y + contentH + 1,
            GuiTheme.CORNER + 1, GuiTheme.OUTLINE);
        context.guiRenderState.up();
        renderGrabBands(context, mouseX, mouseY, contentH);
        renderHeader(context, mouseX, mouseY);

        if (collapsed) {
            return;
        }
        renderRows(context, mouseX, mouseY);
    }

    private void renderGrabBands(GuiGraphicsExtractor context, int mouseX, int mouseY, int contentH) {
        int corner = GuiTheme.CORNER;
        int hot = GuiTheme.accent();
        if (sizeLeft || nearLeft(mouseX, mouseY)) {
            context.fill(x - 1, y - 1 + corner, x, y + contentH + 1 - corner, hot);
        }
        if (sizeRight || nearRight(mouseX, mouseY)) {
            context.fill(x + width, y - 1 + corner, x + width + 1, y + contentH + 1 - corner, hot);
        }
        if (sizeTop || nearTop(mouseX, mouseY)) {
            context.fill(x - 1 + corner, y - 1, x + width + 1 - corner, y, hot);
        }
        if (sizeBottom || nearBottom(mouseX, mouseY)) {
            context.fill(x - 1 + corner, y + contentH, x + width + 1 - corner, y + contentH + 1, hot);
        }
        context.guiRenderState.up();
    }

    private void renderHeader(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        Font font = OfflineClient.MC.font;
        int w = width;
        int h = GuiTheme.HEADER_HEIGHT;
        RenderUtil.roundedRect(context, x, y, x + w, y + h, GuiTheme.CORNER,
            GuiTheme.BG_HEADER, true, collapsed);
        context.guiRenderState.up();
        // Collapsed panels keep the underline clear of the rounded corners.
        int underlineTop = y + h - (collapsed ? 4 : 2);
        int inset = collapsed ? 1 : 0;
        context.fill(x + inset, underlineTop, x + w - inset, underlineTop + 2, GuiTheme.accent());

        int textY = GuiTheme.textY(y, h - 2);
        context.enableScissor(x, y, x + w - MARKER_ZONE, y + h);
        RenderUtil.gradientText(context, font, title, x + GuiTheme.PAD, textY,
            GuiTheme.accentText(), GuiTheme.accent(12));
        context.disableScissor();

        boolean overMarker = mouseX >= x + w - MARKER_ZONE && mouseX < x + w
            && mouseY >= y && mouseY < y + h;
        RenderUtil.chevron(context, x + w - 11, y + (h - 5) / 2, collapsed,
            overMarker ? GuiTheme.TEXT : GuiTheme.TEXT_DIM);
    }

    private void renderRows(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int w = width;
        int viewTop = y + GuiTheme.HEADER_HEIGHT;
        int viewH = viewportHeight();
        boolean scrollable = scrollable();
        int rowW = scrollable ? w - GuiTheme.SCROLLBAR - 1 : w;

        RenderUtil.roundedRect(context, x, viewTop, x + w, viewTop + viewH + 2,
            GuiTheme.CORNER, GuiTheme.BG_PANEL, false, true);
        context.guiRenderState.up();
        if (viewH <= 0) {
            return;
        }

        // Sliders being dragged still get the real mouse position.
        boolean mouseInView = mouseX >= x && mouseX < x + rowW
            && mouseY >= viewTop && mouseY < viewTop + viewH;

        context.enableScissor(x, viewTop, x + rowW, viewTop + viewH);
        int rowY = viewTop - scrollBar.getOffset();
        for (ModuleRow row : rows) {
            int rowH = row.getHeight();
            row.place(x, rowY, rowW, mouseX, mouseY, mouseInView);
            if (rowY + rowH > viewTop && rowY < viewTop + viewH) {
                row.render(context, mouseX, mouseY);
            }
            rowY += rowH;
        }
        context.disableScissor();

        if (scrollable) {
            int trackX = x + w - GuiTheme.SCROLLBAR;
            scrollBar.render(context, trackX, viewTop, viewH, rowsHeight(),
                ScrollBar.isOverTrack(mouseX, mouseY, trackX, viewTop, viewH));
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
        int full = rowsHeight();
        if (sizeBottom) {
            viewHeight = Math.clamp(mouseY - y - GuiTheme.HEADER_HEIGHT - 2, minView(),
                Math.max(minView(), full));
        }
        if (sizeTop) {
            int bottom = y + GuiTheme.HEADER_HEIGHT + viewportHeight() + 2;
            int base = bottom - GuiTheme.HEADER_HEIGHT - 2;
            int maxY = base - minView();
            int minY = Math.clamp(base - full, 0, Math.max(0, maxY));
            int newY = Math.clamp(mouseY, minY, Math.max(minY, maxY));
            viewHeight = base - newY;
            y = newY;
        }
    }

    public boolean mouseClicked(double rawMx, double rawMy, int button) {
        // Whole pixels to match the hover highlight.
        int mx = (int) rawMx;
        int my = (int) rawMy;
        int w = width;

        if (clickEdges(mx, my, button)) {
            return true;
        }
        if (my >= y && my < y + GuiTheme.HEADER_HEIGHT && mx >= x && mx < x + w) {
            clickHeader(mx, my, button);
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

        int trackX = x + w - GuiTheme.SCROLLBAR;
        if (scrollable() && ScrollBar.isOverTrack(mx, my, trackX, viewTop, viewH)) {
            scrollBar.beginDrag(my);
            return true;
        }

        for (ModuleRow row : rows) {
            if (row.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        return true;
    }

    // Checked before the header.
    private boolean clickEdges(int mx, int my, int button) {
        boolean l = nearLeft(mx, my);
        boolean r = nearRight(mx, my);
        boolean t = nearTop(mx, my);
        boolean b = nearBottom(mx, my);
        if (!l && !r && !t && !b) {
            return false;
        }
        if (button == 0) {
            sizeLeft = l;
            sizeRight = r;
            sizeTop = t;
            sizeBottom = b;
        } else if (button == 1) {
            if (l || r) {
                width = GuiTheme.PANEL_WIDTH;
            }
            if (t || b) {
                viewHeight = 0;
                scrollBar.setOffset(0);
            }
        }
        return true;
    }

    private void clickHeader(int mx, int my, int button) {
        if (button == 1 || (button == 0 && mx >= x + width - MARKER_ZONE)) {
            collapsed = !collapsed;
        } else if (button == 0) {
            dragging = true;
            dragOffsetX = mx - x;
            dragOffsetY = my - y;
        }
    }

    public void mouseReleased() {
        dragging = false;
        scrollBar.release();
        sizeLeft = false;
        sizeRight = false;
        sizeTop = false;
        sizeBottom = false;
        for (ModuleRow row : rows) {
            row.mouseReleased();
        }
    }

    // Leaves the panel drag and resize state alone.
    public void releaseDrags() {
        for (ModuleRow row : rows) {
            row.mouseReleased();
        }
    }
}
