package com.jellypudding.offlineclient.hud;

import com.jellypudding.offlineclient.setting.ColorSetting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
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

    // A line in one colour.
    public static TextRow of(Font font, String text, int color) {
        return of(font, List.of(text), List.of(color));
    }

    // A row for each line in the colour of the setting. A rainbow runs down them a row at a time.
    public static List<TextRow> lines(Font font, List<String> lines, ColorSetting color) {
        List<TextRow> rows = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            rows.add(of(font, lines.get(i), color.rowColor(i)));
        }
        return rows;
    }

    // Never less than one pixel. An element with nothing to say still has a size.
    public static int widest(List<TextRow> rows) {
        int widest = 1;
        for (TextRow row : rows) {
            widest = Math.max(widest, row.width());
        }
        return widest;
    }

    // How tall that many rows stand. The last needs no gap under it and there is never less
    // than one row.
    public static int height(Font font, int count) {
        return Math.max(1, count) * LINE - (LINE - font.lineHeight);
    }

    // Where this row starts inside a block as wide as the widest. Nought puts it against the
    // left edge and one against the right edge.
    public int leftIn(int widest, double alignment) {
        return (int) Math.round((widest - width) * alignment);
    }

    // The parts one after another from x.
    public void draw(GuiGraphicsExtractor context, Font font, int x, int y) {
        int left = x;
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            HudElement.write(context, font, part, left, y, colors.get(i));
            left += font.width(part);
        }
    }

    // One under another and each lined up inside the widest.
    public static void draw(GuiGraphicsExtractor context, Font font, List<TextRow> rows, double alignment) {
        int widest = widest(rows);
        int y = 0;
        for (TextRow row : rows) {
            row.draw(context, font, row.leftIn(widest, alignment), y);
            y += LINE;
        }
    }
}
