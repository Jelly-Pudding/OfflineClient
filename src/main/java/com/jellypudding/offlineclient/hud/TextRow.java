package com.jellypudding.offlineclient.hud;

import com.jellypudding.offlineclient.setting.ColorSetting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

public record TextRow(List<String> parts, List<Integer> colors, int width) {

    public static final int LINE = 10;

    public static TextRow of(Font font, List<String> parts, List<Integer> colors) {
        int width = 0;
        for (String part : parts) {
            width += font.width(part);
        }
        return new TextRow(parts, colors, width);
    }

    public static TextRow of(Font font, String text, int color) {
        return of(font, List.of(text), List.of(color));
    }

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

    public static int height(Font font, int count) {
        return Math.max(1, count) * LINE - (LINE - font.lineHeight);
    }

    public int leftIn(int widest, double alignment) {
        return (int) Math.round((widest - width) * alignment);
    }

    public void draw(GuiGraphicsExtractor context, Font font, int x, int y) {
        int left = x;
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            HudElement.write(context, font, part, left, y, colors.get(i));
            left += font.width(part);
        }
    }

    public static void draw(GuiGraphicsExtractor context, Font font, List<TextRow> rows, double alignment) {
        int widest = widest(rows);
        int y = 0;
        for (TextRow row : rows) {
            row.draw(context, font, row.leftIn(widest, alignment), y);
            y += LINE;
        }
    }
}
