package com.jellypudding.offlineclient.gui;

import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// A titled box that drags by its header and resizes by any edge. It holds
// rows of its own inside.
// Each starts at the same width and height and a right click on an edge
// puts that side back.
public abstract class PanelFrame {

    private static final int MIN_VIEW = 16;
    private static final int MIN_WIDTH = 60;
    private static final int MAX_WIDTH = 320;

    // The title stops short of the chevron by this much.
    private static final int MARKER_ZONE = 14;
    // The chevron sits this far in from the right edge. A click on it or close
    // round its outline folds the panel. The rest of the header drags it.
    private static final int MARKER_INSET = 11;
    private static final double MARKER_REACH = 1.5;

    private final String title;
    private final int startWidth;
    private final ScrollBar scrollBar = new ScrollBar();

    private int x;
    private int y;
    private int width;
    private boolean collapsed;

    // The lowest y the box may be put at. Whatever is above owns that band.
    private int topLimit;
    private boolean placed;
    private boolean dragging;
    private int dragOffsetX;
    private int dragOffsetY;

    private boolean sizeLeft;
    private boolean sizeRight;
    private boolean sizeTop;
    private boolean sizeBottom;

    private int viewHeight = GuiTheme.PANEL_VIEW_HEIGHT;
    private int screenHeight;

    protected PanelFrame(String title, int x, int y, int width) {
        this.title = title;
        this.x = x;
        this.y = y;
        this.startWidth = width;
        this.width = width;
    }

    // Everything the rows need whether or not it fits.
    protected abstract int contentHeight();

    protected abstract void renderContent(GuiGraphicsExtractor context, int viewTop, int view,
                                          int rowWidth, int mouseX, int mouseY);

    // True when the click belonged to a row.
    protected abstract boolean clickContent(double mx, double my, int button, int viewTop,
                                            int view, int rowWidth);

    protected abstract void releaseContent();

    // A strip along the bottom the rows never scroll over.
    protected int footerHeight() {
        return 0;
    }

    protected void renderFooter(GuiGraphicsExtractor context, int top, int rowWidth) {
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

    public int getScrollOffset() {
        return scrollBar.getOffset();
    }

    // True once the user has put this box somewhere themselves. Until then a
    // resize of the window lays it out again.
    public boolean isPlaced() {
        return placed;
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

    // Nought or less means the box was never sized and gets the start height.
    private void setViewHeight(int viewHeight) {
        this.viewHeight = viewHeight > 0 ? viewHeight : GuiTheme.PANEL_VIEW_HEIGHT;
    }

    private void setScrollOffset(int offset) {
        scrollBar.setOffset(offset);
    }

    private void setPlaced(boolean placed) {
        this.placed = placed;
    }

    // The place and size and fold of the box. Each owner adds what else it keeps.
    public void writeFrame(JsonObject state) {
        state.addProperty("x", x);
        state.addProperty("y", y);
        state.addProperty("width", width);
        state.addProperty("height", viewHeight);
        state.addProperty("collapsed", collapsed);
        state.addProperty("scroll", scrollBar.getOffset());
        state.addProperty("placed", placed);
    }

    public void readFrame(JsonObject state) {
        if (state.has("x") && state.has("y")) {
            setPosition(state.get("x").getAsInt(), state.get("y").getAsInt());
        }
        if (state.has("width")) {
            setWidth(state.get("width").getAsInt());
        }
        if (state.has("height")) {
            setViewHeight(state.get("height").getAsInt());
        }
        if (state.has("collapsed")) {
            setCollapsed(state.get("collapsed").getAsBoolean());
        }
        if (state.has("scroll")) {
            setScrollOffset(state.get("scroll").getAsInt());
        }
        if (state.has("placed")) {
            setPlaced(state.get("placed").getAsBoolean());
        }
    }

    // Never taller than the rows or the space left below the box.
    protected final int viewportHeight() {
        int full = contentHeight();
        int limit = viewHeight > 0 ? viewHeight : full;
        if (screenHeight > 0) {
            limit = Math.min(limit, screenHeight - y - GuiTheme.HEADER_HEIGHT - footerHeight() - 2);
        }
        return Math.clamp(limit, minView(), full);
    }

    private int minView() {
        return Math.min(MIN_VIEW, contentHeight());
    }

    private int getTotalHeight() {
        if (collapsed) {
            return GuiTheme.HEADER_HEIGHT;
        }
        return GuiTheme.HEADER_HEIGHT + viewportHeight() + 2 + footerHeight();
    }

    // The full rectangle including the grab margin around it.
    public final boolean isOver(double mx, double my) {
        return mx >= x - GuiTheme.GRAB && mx < x + width + GuiTheme.GRAB
            && my >= y - GuiTheme.GRAB && my < y + getTotalHeight() + GuiTheme.GRAB;
    }

    private boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + getTotalHeight();
    }

    // The rectangle the box covers including its outline.
    public final int[] bounds() {
        return new int[] {x - 1, y - 1, x + width + 1, y + getTotalHeight() + 1};
    }

    public final void wheel(double scrollY) {
        int full = contentHeight();
        int view = viewportHeight();
        scrollBar.scroll(ScrollBar.wheelDelta(scrollY, full, view), full, view);
    }

    // The grab bands sit on the border and just outside it.
    private boolean nearLeft(double mx, double my) {
        return mx >= x - GuiTheme.GRAB && mx <= x + 1
            && my >= y - GuiTheme.GRAB && my < y + getTotalHeight() + GuiTheme.GRAB;
    }

    private boolean nearRight(double mx, double my) {
        return mx >= x + width - 1 && mx <= x + width + GuiTheme.GRAB
            && my >= y - GuiTheme.GRAB && my < y + getTotalHeight() + GuiTheme.GRAB;
    }

    private boolean nearTop(double mx, double my) {
        return !collapsed && my >= y - GuiTheme.GRAB && my <= y + 1
            && mx >= x - GuiTheme.GRAB && mx < x + width + GuiTheme.GRAB;
    }

    private boolean nearBottom(double mx, double my) {
        int bottom = y + getTotalHeight();
        return !collapsed && my >= bottom - 1 && my <= bottom + GuiTheme.GRAB
            && mx >= x - GuiTheme.GRAB && mx < x + width + GuiTheme.GRAB;
    }

    private boolean scrollable() {
        return !collapsed && contentHeight() > viewportHeight();
    }

    // Moving and resizing happen before anything draws. A box that covers
    // this one needs to know where it ended up.
    public final void update(int mouseX, int mouseY, int viewW, int viewH, int ceiling) {
        screenHeight = viewH;
        topLimit = ceiling;

        if (dragging) {
            x = Math.clamp(mouseX - dragOffsetX, 0, Math.max(0, viewW - width));
            y = Math.clamp(mouseY - dragOffsetY, ceiling,
                Math.max(ceiling, viewH - GuiTheme.HEADER_HEIGHT));
        }
        applyResize(mouseX, mouseY);
        int full = contentHeight();
        int view = viewportHeight();
        scrollBar.setOffset(ScrollBar.clamp(scrollBar.getOffset(), full, view));
        scrollBar.update(mouseY, full, view);
    }

    public final void render(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int total = getTotalHeight();
        RenderUtil.roundedOutline(context, x - 1, y - 1, x + width + 1, y + total + 1,
            GuiTheme.CORNER + 1, GuiTheme.outline());
        context.guiRenderState.up();
        renderGrabBands(context, mouseX, mouseY, total);
        renderHeader(context, mouseX, mouseY);

        if (collapsed) {
            return;
        }
        renderViewport(context, mouseX, mouseY);
    }

    private void renderGrabBands(GuiGraphicsExtractor context, int mouseX, int mouseY, int total) {
        int corner = GuiTheme.CORNER;
        int hot = GuiTheme.accent();
        if (sizeLeft || nearLeft(mouseX, mouseY)) {
            context.fill(x - 1, y - 1 + corner, x, y + total + 1 - corner, hot);
        }
        if (sizeRight || nearRight(mouseX, mouseY)) {
            context.fill(x + width, y - 1 + corner, x + width + 1, y + total + 1 - corner, hot);
        }
        if (sizeTop || nearTop(mouseX, mouseY)) {
            context.fill(x - 1 + corner, y - 1, x + width + 1 - corner, y, hot);
        }
        if (sizeBottom || nearBottom(mouseX, mouseY)) {
            context.fill(x - 1 + corner, y + total, x + width + 1 - corner, y + total + 1, hot);
        }
        context.guiRenderState.up();
    }

    private void renderHeader(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        Font font = OfflineClient.MC.font;
        int h = GuiTheme.HEADER_HEIGHT;
        RenderUtil.roundedRect(context, x, y, x + width, y + h, GuiTheme.CORNER,
            GuiTheme.bgHeader(), true, collapsed);
        context.guiRenderState.up();
        // A collapsed box keeps the underline clear of the rounded corners.
        int underlineTop = y + h - (collapsed ? 4 : 2);
        int inset = collapsed ? 1 : 0;
        context.fill(x + inset, underlineTop, x + width - inset, underlineTop + 2,
            GuiTheme.accent());

        int textY = GuiTheme.textY(y, h - 2);
        context.enableScissor(x, y, x + width - MARKER_ZONE, y + h);
        RenderUtil.gradientText(context, font, title, x + GuiTheme.PAD, textY,
            GuiTheme.accentText(), GuiTheme.accent(12));
        context.disableScissor();

        RenderUtil.chevron(context, markerX(), markerY(), collapsed,
            overMarker(mouseX, mouseY) ? GuiTheme.text() : GuiTheme.textDim());
    }

    private int markerX() {
        return x + width - MARKER_INSET;
    }

    private int markerY() {
        return y + (GuiTheme.HEADER_HEIGHT - 2 - RenderUtil.CHEVRON_HEIGHT) / 2;
    }

    private boolean overMarker(int mx, int my) {
        return RenderUtil.overChevron(mx, my, markerX(), markerY(), collapsed, MARKER_REACH);
    }

    private void renderViewport(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int viewTop = y + GuiTheme.HEADER_HEIGHT;
        int view = viewportHeight();
        int full = contentHeight();
        int rowW = ScrollBar.rowWidth(width, full, view);

        RenderUtil.roundedRect(context, x, viewTop, x + width, viewTop + view + 2 + footerHeight(),
            GuiTheme.CORNER, GuiTheme.bgPanel(), false, true);
        context.guiRenderState.up();
        if (view <= 0) {
            return;
        }

        context.enableScissor(x, viewTop, x + rowW, viewTop + view);
        renderContent(context, viewTop, view, rowW, mouseX, mouseY);
        context.disableScissor();

        if (scrollable()) {
            int trackX = ScrollBar.trackX(x, width);
            scrollBar.render(context, trackX, viewTop, view, full,
                ScrollBar.isOverTrack(mouseX, mouseY, trackX, viewTop, view));
        }
        if (footerHeight() > 0) {
            renderFooter(context, viewTop + view + 2, rowW);
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
        int full = contentHeight();
        if (sizeBottom) {
            viewHeight = Math.clamp(mouseY - y - GuiTheme.HEADER_HEIGHT - footerHeight() - 2,
                minView(), Math.max(minView(), full));
        }
        if (sizeTop) {
            int base = y + viewportHeight();
            int maxY = base - minView();
            int minY = Math.clamp(base - full, topLimit, Math.max(topLimit, maxY));
            int newY = Math.clamp(mouseY, minY, Math.max(minY, maxY));
            viewHeight = base - newY;
            y = newY;
        }
    }

    public final boolean mouseClicked(double rawMx, double rawMy, int button) {
        // Whole pixels to match the hover highlight.
        int mx = (int) rawMx;
        int my = (int) rawMy;

        if (clickEdges(mx, my, button)) {
            return true;
        }
        if (my >= y && my < y + GuiTheme.HEADER_HEIGHT && mx >= x && mx < x + width) {
            clickHeader(mx, my, button);
            return true;
        }
        if (collapsed) {
            return false;
        }

        int viewTop = y + GuiTheme.HEADER_HEIGHT;
        int view = viewportHeight();
        if (mx < x || mx >= x + width || my < viewTop || my >= viewTop + view) {
            return contains(mx, my);
        }

        int trackX = ScrollBar.trackX(x, width);
        if (scrollable() && ScrollBar.isOverTrack(mx, my, trackX, viewTop, view)) {
            scrollBar.beginDrag(my);
            return true;
        }
        clickContent(mx, my, button, viewTop, view, ScrollBar.rowWidth(width, contentHeight(), view));
        return true;
    }

    private boolean clickEdges(int mx, int my, int button) {
        boolean l = nearLeft(mx, my);
        boolean r = nearRight(mx, my);
        boolean t = nearTop(mx, my);
        boolean b = nearBottom(mx, my);
        if (!l && !r && !t && !b) {
            return false;
        }
        if (InputUtil.isLeft(button)) {
            sizeLeft = l;
            sizeRight = r;
            sizeTop = t;
            sizeBottom = b;
            placed = true;
        } else if (InputUtil.isRight(button)) {
            placed = true;
            if (l || r) {
                width = startWidth;
            }
            if (t || b) {
                viewHeight = GuiTheme.PANEL_VIEW_HEIGHT;
                scrollBar.setOffset(0);
            }
        }
        return true;
    }

    private void clickHeader(int mx, int my, int button) {
        if (InputUtil.isRight(button) || (InputUtil.isLeft(button) && overMarker(mx, my))) {
            collapsed = !collapsed;
        } else if (InputUtil.isLeft(button)) {
            dragging = true;
            placed = true;
            dragOffsetX = mx - x;
            dragOffsetY = my - y;
        }
    }

    public final void mouseReleased() {
        dragging = false;
        scrollBar.release();
        sizeLeft = false;
        sizeRight = false;
        sizeTop = false;
        sizeBottom = false;
        releaseContent();
    }

    // Leaves the drag and resize state alone.
    public final void releaseDrags() {
        releaseContent();
    }
}
