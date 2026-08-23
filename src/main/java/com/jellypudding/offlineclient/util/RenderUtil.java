package com.jellypudding.offlineclient.util;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

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

    public static void gradientText(GuiGraphicsExtractor context, Font font, String text,
                                    float x, float y, int from, int to, float scale) {
        gradientText(context, font, text, x, y, from, to, scale, 1f);
    }

    public static void gradientText(GuiGraphicsExtractor context, Font font, String text,
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

    // Seven pixels square.
    public static void star(GuiGraphicsExtractor context, int x, int y, int color) {
        context.fill(x + 3, y, x + 4, y + 1, color);
        context.fill(x + 2, y + 1, x + 5, y + 2, color);
        context.fill(x, y + 2, x + 7, y + 3, color);
        context.fill(x + 1, y + 3, x + 6, y + 5, color);
        context.fill(x, y + 5, x + 2, y + 7, color);
        context.fill(x + 5, y + 5, x + 7, y + 7, color);
    }

    // Seven pixels square.
    public static void magnifier(GuiGraphicsExtractor context, int x, int y, int color) {
        context.fill(x + 1, y, x + 5, y + 1, color);
        context.fill(x, y + 1, x + 1, y + 4, color);
        context.fill(x + 5, y + 1, x + 6, y + 4, color);
        context.fill(x + 1, y + 4, x + 5, y + 5, color);
        context.fill(x + 5, y + 5, x + 7, y + 7, color);
    }

    // Five pixels square.
    public static void tick(GuiGraphicsExtractor context, int x, int y, int color) {
        context.fill(x, y + 2, x + 1, y + 4, color);
        context.fill(x + 1, y + 3, x + 2, y + 5, color);
        context.fill(x + 2, y + 2, x + 3, y + 4, color);
        context.fill(x + 3, y + 1, x + 4, y + 3, color);
        context.fill(x + 4, y, x + 5, y + 2, color);
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
