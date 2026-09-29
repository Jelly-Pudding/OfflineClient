package com.jellypudding.offlineclient.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

// One line of a HUD element cut into parts that each carry their own colour.
public record TextRow(List<String> parts, List<Integer> colors, int width) {

    // How far down each line of HUD text starts from the one above.
    public static final int LINE = 10;

    public static TextRow of(Font font, List<String> parts, List<Integer> colors) {
        int width = 0;
        for (String part : parts) {
            width += font.width(part);
        }
        return new TextRow(parts, colors, width);
    }

    // Never less than one pixel. An element with nothing to say still has a size.
    public static int widest(List<TextRow> rows) {
        int widest = 1;
        for (TextRow row : rows) {
            widest = Math.max(widest, row.width());
        }
        return widest;
    }

    // One under another. The alignment places each row inside the widest from its left
    // edge at nought to its right edge at one.
    public static void draw(GuiGraphicsExtractor context, Font font, List<TextRow> rows, double alignment) {
        int widest = widest(rows);
        int y = 0;
        for (TextRow row : rows) {
            int x = (int) Math.round((widest - row.width()) * alignment);
            for (int i = 0; i < row.parts().size(); i++) {
                String part = row.parts().get(i);
                context.text(font, part, x, y, row.colors().get(i), true);
                x += font.width(part);
            }
            y += LINE;
        }
    }
}
