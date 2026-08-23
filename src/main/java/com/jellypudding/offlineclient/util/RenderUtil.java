package com.jellypudding.offlineclient.util;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

import java.util.List;

// 2D drawing helpers on top of GuiGraphicsExtractor.
public final class RenderUtil {

    private RenderUtil() {
    }

    public static void gradientText(GuiGraphicsExtractor context, Font font, String text,
                                    int x, int y, int from, int to) {
        gradientText(context, font, text, x, y, from, to, 1f);
    }

    // Alpha runs from 0 to 1.
    public static void gradientText(GuiGraphicsExtractor context, Font font, String text,
                                    int x, int y, int from, int to, float alpha) {
        if (alpha < 0.05f) {
            return;
        }
        context.guiRenderState.up();
        int length = text.length();
        for (int i = 0; i < length; i++) {
            String ch = String.valueOf(text.charAt(i));
            int color = ColorUtil.lerp(from, to, length <= 1 ? 0 : (float) i / (length - 1));
            context.text(font, ch, x, y, ColorUtil.fade(color, alpha), true);
            x += font.width(ch);
        }
    }

    public static void gradientTextScaled(GuiGraphicsExtractor context, Font font, String text,
                                          float x, float y, int from, int to, float scale) {
        gradientTextScaled(context, font, text, x, y, from, to, scale, 1f);
    }

    public static void gradientTextScaled(GuiGraphicsExtractor context, Font font, String text,
                                          float x, float y, int from, int to, float scale, float alpha) {
        if (alpha < 0.05f) {
            return;
        }
        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);
        gradientText(context, font, text, 0, 0, from, to, alpha);
        pose.popMatrix();
    }

    // An animated rainbow wave with one colour per character.
    public static void rainbowText(GuiGraphicsExtractor context, Font font, String text, int x, int y) {
        rainbowText(context, font, text, x, y, 1f, 1f);
    }

    public static void rainbowText(GuiGraphicsExtractor context, Font font, String text,
                                   float x, float y, float scale) {
        rainbowText(context, font, text, x, y, scale, 1f);
    }

    public static void rainbowText(GuiGraphicsExtractor context, Font font, String text,
                                   float x, float y, float scale, float alpha) {
        if (alpha < 0.05f) {
            return;
        }
        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);
        context.guiRenderState.up();
        int cx = 0;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            context.text(font, ch, cx, 0, ColorUtil.fade(ColorUtil.rainbow(i), alpha), true);
            cx += font.width(ch);
        }
        pose.popMatrix();
    }

    public static void borderedRect(GuiGraphicsExtractor context, int x, int y, int x2, int y2,
                                    int fillColor, int borderColor) {
        context.fill(x, y, x2, y2, borderColor);
        context.fill(x + 1, y + 1, x2 - 1, y2 - 1, fillColor);
    }

    public static void roundedRect(GuiGraphicsExtractor context, int x, int y, int x2, int y2,
                                   int radius, int color) {
        roundedRect(context, x, y, x2, y2, radius, color, true, true);
    }

    // Each end can keep square corners.
    public static void roundedRect(GuiGraphicsExtractor context, int x, int y, int x2, int y2,
                                   int radius, int color, boolean roundTop, boolean roundBottom) {
        int r = Math.min(radius, Math.min((x2 - x) / 2, (y2 - y) / 2));
        if (r <= 0) {
            context.fill(x, y, x2, y2, color);
            return;
        }
        context.fill(x, y + (roundTop ? r : 0), x2, y2 - (roundBottom ? r : 0), color);
        for (int i = 0; i < r; i++) {
            // Corner inset taken from a circle of the same radius.
            double dy = r - i - 0.5;
            int inset = (int) Math.ceil(r - Math.sqrt(r * r - dy * dy));
            if (roundTop) {
                context.fill(x + inset, y + i, x2 - inset, y + i + 1, color);
            }
            if (roundBottom) {
                context.fill(x + inset, y2 - i - 1, x2 - inset, y2 - i, color);
            }
        }
    }

    public static void roundedBorderedRect(GuiGraphicsExtractor context, int x, int y, int x2, int y2,
                                           int radius, int fillColor, int borderColor) {
        roundedRect(context, x, y, x2, y2, radius, borderColor);
        roundedRect(context, x + 1, y + 1, x2 - 1, y2 - 1, radius - 1, fillColor);
    }

    public static void shadow(GuiGraphicsExtractor context, int x, int y, int x2, int y2, int spread) {
        for (int i = spread; i >= 1; i--) {
            context.fill(x - i, y - i, x2 + i, y2 + i, 0x30000000);
        }
    }

    public static void toggle(GuiGraphicsExtractor context, int x, int y, int w, int h,
                             boolean on, int onColor, int offColor, int knobColor) {
        roundedRect(context, x, y, x + w, y + h, h / 2, on ? onColor : offColor);
        context.guiRenderState.up();
        int knob = h - 4;
        int knobX = on ? x + w - 2 - knob : x + 2;
        roundedRect(context, knobX, y + 2, knobX + knob, y + 2 + knob, knob / 2, knobColor);
    }

    // Six wide and three tall.
    public static void chevron(GuiGraphicsExtractor context, int x, int y, boolean down, int color) {
        for (int i = 0; i < 3; i++) {
            int row = down ? y + i : y + 2 - i;
            context.fill(x + i, row, x + 6 - i, row + 1, color);
        }
    }

    // A hollow star reads as not chosen far better than a faded solid one.
    private static final String[] STAR = {
        "....#....",
        "...###...",
        "...###...",
        "#########",
        ".#######.",
        "..#####..",
        "..#####..",
        ".##...##.",
        "##.....##",
    };

    public static final int STAR_SIZE = STAR.length;

    public static void star(GuiGraphicsExtractor context, int x, int y, int color, boolean filled) {
        for (int row = 0; row < STAR.length; row++) {
            for (int col = 0; col < STAR[row].length(); col++) {
                if (!starSolid(row, col) || (!filled && !starEdge(row, col))) {
                    continue;
                }
                context.fill(x + col, y + row, x + col + 1, y + row + 1, color);
            }
        }
    }

    // A lit cell with any unlit neighbour is part of the outline.
    private static boolean starEdge(int row, int col) {
        return !starSolid(row - 1, col) || !starSolid(row + 1, col)
            || !starSolid(row, col - 1) || !starSolid(row, col + 1);
    }

    private static boolean starSolid(int row, int col) {
        return row >= 0 && row < STAR.length
            && col >= 0 && col < STAR[row].length()
            && STAR[row].charAt(col) == '#';
    }

    // Five pixels square.
    public static void tick(GuiGraphicsExtractor context, int x, int y, int color) {
        context.fill(x, y + 2, x + 1, y + 4, color);
        context.fill(x + 1, y + 3, x + 2, y + 5, color);
        context.fill(x + 2, y + 2, x + 3, y + 4, color);
        context.fill(x + 3, y + 1, x + 4, y + 3, color);
        context.fill(x + 4, y, x + 5, y + 2, color);
    }

    // Behind a world label. Text over bright terrain is unreadable without it.
    public static final int LABEL_BACKGROUND = 0x90000000;

    private static final int TOOLTIP_FILL = 0xF00E0E14;
    private static final int TOOLTIP_BORDER = 0x50FFFFFF;
    private static final int TOOLTIP_TEXT = 0xFFD8D8E4;

    /**
     * A scaled label centred on a screen point. Each part carries its own
     * colour and they run left to right on one line.
     */
    public static void label(GuiGraphicsExtractor context, Font font, double screenX, double screenY,
                             float scale, List<String> parts, List<Integer> colors) {
        int width = 0;
        for (String part : parts) {
            width += font.width(part);
        }
        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate((float) screenX, (float) screenY);
        pose.scale(scale, scale);
        int half = width / 2;
        context.fill(-half - 2, -2, half + 2, font.lineHeight, LABEL_BACKGROUND);
        context.guiRenderState.up();
        int x = -half;
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            context.text(font, part, x, -1, colors.get(i), false);
            x += font.width(part);
        }
        pose.popMatrix();
    }

    // Sits beside the cursor and stays inside the screen.
    public static void tooltip(GuiGraphicsExtractor context, Font font, List<String> lines,
                               int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (lines.isEmpty()) {
            return;
        }
        int lineHeight = font.lineHeight + 1;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        int height = lines.size() * lineHeight;
        int x = mouseX + 10;
        int y = mouseY + 10;
        if (x + width + 4 > screenWidth) {
            x = Math.max(4, mouseX - width - 12);
        }
        if (y + height + 4 > screenHeight) {
            y = Math.max(4, screenHeight - height - 4);
        }
        context.guiRenderState.up();
        roundedBorderedRect(context, x - 4, y - 3, x + width + 4, y + height + 2,
            3, TOOLTIP_FILL, TOOLTIP_BORDER);
        context.guiRenderState.up();
        for (int i = 0; i < lines.size(); i++) {
            context.text(font, lines.get(i), x, y + i * lineHeight, TOOLTIP_TEXT, false);
        }
    }

    // Horizontal hue ramp. The vanilla gradient fill only runs top to bottom.
    public static void hueBar(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        int band = 3;
        for (int i = 0; i < width; i += band) {
            int end = Math.min(width, i + band);
            context.fill(x + i, y, x + end, y + height, ColorUtil.hsv(360f * i / width, 0.8f, 1f));
        }
    }
}
