package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.hud.Box;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

// A piece lines up with the side of the screen it sits in and switches side at the thirds.
final class HudSnap {

    static final int REACH = 4;

    private static final int THIRDS = 3;
    private static final int THIRD_LINE = 0x40FFFFFF;

    private enum Part {
        START, MIDDLE, END;

        private int line(int start, int size) {
            return switch (this) {
                case START -> start;
                case MIDDLE -> start + size / 2;
                case END -> start + size - 1;
            };
        }
    }

    private record Stop(int at, Part part) {
    }

    private Part across;
    private Part down;
    private Box landed;

    void reset() {
        across = null;
        down = null;
        landed = null;
    }

    int stickAcross(int left, int width, int room, List<Box> others) {
        Stop stop = nearest(left, width, room, others, true);
        across = stop == null ? null : stop.part();
        return stop == null ? left : stop.at();
    }

    int stickDown(int top, int height, int room, List<Box> others) {
        Stop stop = nearest(top, height, room, others, false);
        down = stop == null ? null : stop.part();
        return stop == null ? top : stop.at();
    }

    void loose() {
        across = null;
        down = null;
    }

    // A part of the game's own HUD that went home stuck there on both axes.
    void home(Box home) {
        across = Part.START;
        down = Part.START;
        landed = home;
    }

    void landed(Box box) {
        landed = box;
    }

    void draw(GuiGraphicsExtractor context, int width, int height, int stuckColour) {
        for (int third = 1; third < THIRDS; third++) {
            int x = width * third / THIRDS;
            int y = height * third / THIRDS;
            context.fill(x, 0, x + 1, height, THIRD_LINE);
            context.fill(0, y, width, y + 1, THIRD_LINE);
        }
        if (landed == null) {
            return;
        }
        if (across != null) {
            int x = across.line(landed.left(), landed.width());
            context.fill(x, 0, x + 1, height, stuckColour);
        }
        if (down != null) {
            int y = down.line(landed.top(), landed.height());
            context.fill(0, y, width, y + 1, stuckColour);
        }
    }

    private static Stop nearest(int edge, int size, int room, List<Box> others, boolean horizontal) {
        List<Stop> stops = new ArrayList<>();
        stops.add(new Stop(0, Part.START));
        stops.add(new Stop((room - size) / 2, Part.MIDDLE));
        stops.add(new Stop(room - size, Part.END));
        for (Box other : others) {
            int start = horizontal ? other.left() : other.top();
            int length = horizontal ? other.width() : other.height();
            stops.add(new Stop(start, Part.START));
            stops.add(new Stop(start + length - size, Part.END));
            stops.add(new Stop(start + length, Part.START));
            stops.add(new Stop(start - size, Part.END));
        }
        Stop best = null;
        int bestGap = REACH + 1;
        for (Stop stop : stops) {
            int gap = Math.abs(edge - stop.at());
            if (gap < bestGap) {
                best = stop;
                bestGap = gap;
            }
        }
        return best;
    }
}
