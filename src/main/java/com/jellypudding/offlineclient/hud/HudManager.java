package com.jellypudding.offlineclient.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

// Works out where each element sits and draws it. The editor screen asks for the
// same layout. What you drag is exactly what you see whilst playing.
public final class HudManager {

    // Kept clear of the screen edge. Nothing touches the very border.
    private static final int MARGIN = 3;

    // An element whose anchor falls in the first third of the screen keeps its left
    // edge on that spot and the last third keeps its right edge.
    private static final double FIRST_THIRD = 1 / 3.0;
    private static final double LAST_THIRD = 2 / 3.0;

    public record Placement(HudElement element, int left, int top, int width, int height) {
    }

    private final List<HudElement> elements = new ArrayList<>();

    public void add(HudElement element) {
        elements.add(element);
    }

    // Where each visible element lands on a screen of the given size.
    public List<Placement> layout(Font font, int screenWidth, int screenHeight, boolean editing) {
        List<Placement> placed = new ArrayList<>();
        for (HudElement element : elements) {
            if (!editing && !element.visible()) {
                continue;
            }
            if (editing && !element.isActive()) {
                continue;
            }
            float scale = element.scale();
            int width = Math.max(1, Math.round(element.width(font) * scale));
            int height = Math.max(1, Math.round(element.height(font) * scale));
            int left = corner(element.xPercent(), screenWidth, width);
            int top = corner(element.yPercent(), screenHeight, height);
            placed.add(new Placement(element, left, top, width, height));
        }
        return placed;
    }

    // The top or left edge for an anchor given as a share of the screen.
    private static int corner(double percent, int room, int size) {
        double share = Math.clamp(percent / 100.0, 0, 1);
        double anchor = share * room;
        double edge = anchor - align(share) * size;
        return (int) Math.round(Math.clamp(edge, MARGIN, Math.max(MARGIN, room - size - MARGIN)));
    }

    // Nought pins the near edge and one the far edge and a half the middle.
    private static double align(double share) {
        if (share < FIRST_THIRD) {
            return 0;
        }
        return share > LAST_THIRD ? 1 : 0.5;
    }

    // The share of the screen an element pinned at this edge would be stored as.
    public static double shareOf(int edge, int size, int room) {
        if (room <= 0) {
            return 0;
        }
        double middle = (edge + size / 2.0) / room;
        return Math.clamp((edge + align(middle) * size) / room, 0, 1);
    }

    public void render(GuiGraphicsExtractor context, Font font, boolean editing) {
        int screenWidth = context.guiWidth();
        int screenHeight = context.guiHeight();
        for (Placement placement : layout(font, screenWidth, screenHeight, editing)) {
            draw(context, font, placement);
        }
    }

    public void draw(GuiGraphicsExtractor context, Font font, Placement placement) {
        float scale = placement.element().scale();
        context.pose().pushMatrix();
        context.pose().translate(placement.left(), placement.top());
        if (scale != 1f) {
            context.pose().scale(scale, scale);
        }
        placement.element().render(context, font);
        context.pose().popMatrix();
    }
}
