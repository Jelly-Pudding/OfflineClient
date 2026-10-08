package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.hud.Box;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

// Where a piece moved in the HUD editor sticks and the guides drawn whilst it moves. A piece
// lines up with the side of the screen it sits in and switches side at the thirds.
final class HudSnap {

    // How close an edge has to be before it sticks.
    static final int REACH = 4;

    private static final int THIRDS = 3;
    // The thirds stay faint over anything. An edge that stuck is drawn in a brighter colour.
    private static final int THIRD_LINE = 0x40FFFFFF;

    // The part of the moving box that lines up with a stop.
    private enum Part {
        START, MIDDLE, END;

        // The column or row of the box its guide runs along.
        private int line(int start, int size) {
            return switch (this) {
                case START -> start;
                case MIDDLE -> start + size / 2;
                case END -> start + size - 1;
            };
        }
    }

    // Where the moving box would start for its part to line up with something.
    private record Stop(int at, Part part) {
    }

    // The part that stuck on each axis. Null where nothing did.
    private Part across;
    private Part down;
    // Where the moving box came to rest. Null until it has moved.
    private Box landed;

    // Each move starts with nothing stuck.
    void reset() {
        across = null;
        down = null;
        landed = null;
    }

    // A left edge in reach of a stop jumps onto it.
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

    // Shift moves freely.
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

    // Every line runs the full width or height of the screen.
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

    // The screen edges and middle and the edges of the other pieces. Null when none is in reach.
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
