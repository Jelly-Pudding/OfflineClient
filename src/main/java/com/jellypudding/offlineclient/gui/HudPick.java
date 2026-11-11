package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.hud.Box;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

// The pieces picked together in the HUD editor. A drag across empty space draws a box and
// every piece it touches is picked once the button comes up.
final class HudPick {

    // How strongly the box and every picked piece are tinted with the accent.
    private static final float WASH = 0.18f;

    private final Set<HudElement> picked = new HashSet<>();

    // Where the box began and where the pointer has dragged it. Only whilst one is drawn.
    private boolean drawing;
    private double fromX;
    private double fromY;
    private double toX;
    private double toY;

    boolean has(HudElement element) {
        return picked.contains(element);
    }

    boolean drawing() {
        return drawing;
    }

    void clear() {
        picked.clear();
    }

    // Lets go of every piece that is not among the given ones.
    void keepOnly(Collection<HudElement> pieces) {
        picked.retainAll(pieces);
    }

    void begin(double x, double y) {
        drawing = true;
        fromX = x;
        fromY = y;
        toX = x;
        toY = y;
    }

    void drag(double x, double y) {
        toX = x;
        toY = y;
    }

    // The pieces the box touched become the pick and the box goes.
    void finish(Collection<HudElement> touched) {
        picked.clear();
        picked.addAll(touched);
        drawing = false;
    }

    // Whilst the box is drawn a piece shows as picked as soon as the box touches it.
    boolean shows(HudElement element, Box frame) {
        return drawing ? touches(frame) : picked.contains(element);
    }

    boolean touches(Box frame) {
        Box box = box();
        return box.left() < frame.left() + frame.width() && frame.left() < box.left() + box.width()
            && box.top() < frame.top() + frame.height() && frame.top() < box.top() + box.height();
    }

    // Laid over a picked piece inside its outline.
    void tint(GuiGraphicsExtractor context, Box frame) {
        context.fill(frame.left(), frame.top(), frame.left() + frame.width(), frame.top() + frame.height(),
            ColorUtil.fade(GuiTheme.accent(), WASH));
    }

    void drawBox(GuiGraphicsExtractor context) {
        if (!drawing) {
            return;
        }
        Box box = box();
        tint(context, box);
        context.outline(box.left(), box.top(), box.width(), box.height(), GuiTheme.accent());
    }

    // Whole pixels round both corners and never less than one either way.
    private Box box() {
        int left = (int) Math.floor(Math.min(fromX, toX));
        int top = (int) Math.floor(Math.min(fromY, toY));
        int right = Math.max(left + 1, (int) Math.ceil(Math.max(fromX, toX)));
        int bottom = Math.max(top + 1, (int) Math.ceil(Math.max(fromY, toY)));
        return new Box(left, top, right - left, bottom - top);
    }
}
