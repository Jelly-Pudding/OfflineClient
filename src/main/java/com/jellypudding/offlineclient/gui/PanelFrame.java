package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// A titled box that drags by its header and resizes by any edge. Every
// draggable box in the client is one of these with its own rows inside.
public abstract class PanelFrame {

    protected static final int MIN_VIEW = 16;
    protected static final int MIN_WIDTH = 60;
    protected static final int MAX_WIDTH = 320;

    private static final int GRAB = 4;
    private static final int MARKER_ZONE = 14;

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

    private int viewHeight;
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

    public int getViewHeight() {
        return viewHeight;
    }

    public int getScrollOffset() {
        return scrollBar.getOffset();
    }

    public boolean isDragging() {
        return dragging;
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

    public void setViewHeight(int viewHeight) {
        this.viewHeight = Math.max(0, viewHeight);
    }

    public void setScrollOffset(int offset) {
        scrollBar.setOffset(offset);
    }

    public void setPlaced(boolean placed) {
        this.placed = placed;
    }

    protected int getScreenHeight() {
        return screenHeight;
    }

    // Never taller than the rows or the space left below the box.
    protected final int viewportHeight() {
        int full = contentHeight();
        int limit = viewHeight > 0 ? viewHeight : full;
        if (screenHeight > 0) {
            limit = Math.min(limit, screenHeight - y - GuiTheme.HEADER_HEIGHT - footerHeight() - 2);
        }
        return Math.clamp(limit, Math.min(MIN_VIEW, full), full);
    }

    private int minView() {
        return Math.min(MIN_VIEW, contentHeight());
    }

    public final int getTotalHeight() {
        if (collapsed) {
            return GuiTheme.HEADER_HEIGHT;
        }
        return GuiTheme.HEADER_HEIGHT + viewportHeight() + 2 + footerHeight();
    }

    // The full rectangle including the grab margin around it.
    public final boolean isOver(double mx, double my) {
        return mx >= x - GRAB && mx < x + width + GRAB
            && my >= y - GRAB && my < y + getTotalHeight() + GRAB;
    }

    public final boolean contains(double mx, double my) {
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
        return mx >= x - GRAB && mx <= x + 1 && my >= y - GRAB && my < y + getTotalHeight() + GRAB;
    }

    private boolean nearRight(double mx, double my) {
        return mx >= x + width - 1 && mx <= x + width + GRAB
            && my >= y - GRAB && my < y + getTotalHeight() + GRAB;
    }

    private boolean nearTop(double mx, double my) {
        return !collapsed && my >= y - GRAB && my <= y + 1 && mx >= x - GRAB && mx < x + width + GRAB;
    }

    private boolean nearBottom(double mx, double my) {
        int bottom = y + getTotalHeight();
        return !collapsed && my >= bottom - 1 && my <= bottom + GRAB
            && mx >= x - GRAB && mx < x + width + GRAB;
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

        boolean overMarker = mouseX >= x + width - MARKER_ZONE && mouseX < x + width
            && mouseY >= y && mouseY < y + h;
        RenderUtil.chevron(context, x + width - 11, y + (h - 5) / 2, collapsed,
            overMarker ? GuiTheme.text() : GuiTheme.textDim());
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
                viewHeight = 0;
                scrollBar.setOffset(0);
            }
        }
        return true;
    }

    private void clickHeader(int mx, int my, int button) {
        if (InputUtil.isRight(button) || (InputUtil.isLeft(button) && mx >= x + width - MARKER_ZONE)) {
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
