package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// A scroll offset with a draggable thumb.
public final class ScrollBar {

    private static final int MIN_THUMB = 12;
    // Extra grab room to the left of the track.
    private static final int GRAB = 3;

    private int offset;
    private boolean dragging;
    private int dragStartY;
    private int dragStartOffset;

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = Math.max(0, offset);
    }

    public boolean isDragging() {
        return dragging;
    }

    public static int clamp(int value, int total, int view) {
        return Math.clamp(value, 0, Math.max(0, total - view));
    }

    // A positive delta moves the content down.
    public void scroll(int delta, int total, int view) {
        offset = clamp(offset - delta, total, view);
    }

    // Called once per frame.
    public void update(int mouseY, int total, int view) {
        if (dragging && total > view && view > 0) {
            offset = dragStartOffset + (mouseY - dragStartY) * total / view;
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

    // The grab band is wider than the track.
    public static boolean isOverTrack(double mx, double my, int trackX, int top, int view) {
        return mx >= trackX - GRAB && mx < trackX + GuiTheme.SCROLLBAR
            && my >= top && my < top + view;
    }

    // Draws nothing unless the content overflows.
    public void render(GuiGraphicsExtractor context, int trackX, int top, int view, int total,
                       boolean hovered) {
        if (view <= 0 || total <= view) {
            return;
        }
        int right = trackX + GuiTheme.SCROLLBAR;
        RenderUtil.roundedRect(context, trackX, top, right, top + view, 2, GuiTheme.SCROLL_TRACK);
        context.guiRenderState.up();
        int thumbH = Math.min(view, Math.max(MIN_THUMB, view * view / total));
        int range = total - view;
        int thumbY = top + (view - thumbH) * Math.clamp(offset, 0, range) / range;
        RenderUtil.roundedRect(context, trackX, thumbY, right, thumbY + thumbH, 2,
            dragging || hovered ? GuiTheme.accent() : GuiTheme.SCROLL_THUMB);
    }
}
