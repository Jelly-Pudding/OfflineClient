package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class ScrollBar {

    private static final int MIN_THUMB = 12;

    private static final int WHEEL_STEP = 16;
    private static final int GRAB = 3;

    private int offset;
    private boolean dragging;
    private int dragStartY;
    private int dragStartOffset;

    public static int wheelDelta(double scrollY) {
        return (int) Math.round(scrollY * WHEEL_STEP);
    }

    // A list several screens long moves further per notch. Up to double the step.
    public static int wheelDelta(double scrollY, int total, int view) {
        double screens = view <= 0 ? 1 : (double) total / view;
        return (int) Math.round(scrollY * WHEEL_STEP * Math.clamp(screens / 2, 1, 2));
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = Math.max(0, offset);
    }

    public static int clamp(int value, int total, int view) {
        return Math.clamp(value, 0, Math.max(0, total - view));
    }

    // A positive delta moves the content down.
    public void scroll(int delta, int total, int view) {
        offset = clamp(offset - delta, total, view);
    }

    public static int rowWidth(int width, int total, int view) {
        return total > view ? width - GuiTheme.SCROLL_GUTTER : width;
    }

    public static int trackX(int x, int width) {
        return x + width - GuiTheme.SCROLLBAR;
    }

    private static int thumbHeight(int view, int total) {
        return Math.min(view, Math.max(MIN_THUMB, view * view / total));
    }

    public void update(int mouseY, int total, int view) {
        if (dragging && total > view && view > 0) {
            int travel = Math.max(1, view - thumbHeight(view, total));
            offset = dragStartOffset + (mouseY - dragStartY) * (total - view) / travel;
        }
        offset = clamp(offset, total, view);
    }

    public void beginDrag(int mouseY) {
        dragging = true;
        dragStartY = mouseY;
        dragStartOffset = offset;
    }

    public void release() {
        dragging = false;
    }

    public static boolean isOverTrack(double mx, double my, int trackX, int top, int view) {
        return mx >= trackX - GRAB && mx < trackX + GuiTheme.SCROLLBAR
            && my >= top && my < top + view;
    }

    public void render(GuiGraphicsExtractor context, int trackX, int top, int view, int total,
                       boolean hovered) {
        if (view <= 0 || total <= view) {
            return;
        }
        int right = trackX + GuiTheme.SCROLLBAR;
        RenderUtil.roundedRect(context, trackX, top, right, top + view, 2, GuiTheme.SCROLL_TRACK);
        context.guiRenderState.up();
        int thumbH = thumbHeight(view, total);
        int range = total - view;
        int thumbY = top + (view - thumbH) * Math.clamp(offset, 0, range) / range;
        RenderUtil.roundedRect(context, trackX, thumbY, right, thumbY + thumbH, 2,
            dragging || hovered ? GuiTheme.accent() : GuiTheme.scrollThumb());
    }
}
