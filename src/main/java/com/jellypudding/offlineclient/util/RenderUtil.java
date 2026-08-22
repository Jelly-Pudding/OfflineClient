package com.jellypudding.offlineclient.util;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

/**
 * 2D drawing helpers on top of GuiGraphicsExtractor.
 */
public final class RenderUtil {

    private RenderUtil() {
    }

    /** Draws text with a smooth color gradient across its characters. */
    public static void gradientText(GuiGraphicsExtractor context, Font font, String text,
                                    int x, int y, int from, int to) {
        gradientText(context, font, text, x, y, from, to, 1f);
    }

    /** Gradient text with a fade. Alpha runs from 0 to 1. */
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

    /** Scaled version of the gradient text. */
    public static void gradientText(GuiGraphicsExtractor context, Font font, String text,
                                    float x, float y, int from, int to, float scale) {
        gradientText(context, font, text, x, y, from, to, scale, 1f);
    }

    /** Scaled gradient text with a fade. */
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

    /** Draws text as an animated rainbow wave with one color per character. */
    public static void rainbowText(GuiGraphicsExtractor context, Font font, String text, int x, int y) {
        rainbowText(context, font, text, x, y, 1f, 1f);
    }

    /** Scaled version of the rainbow text. */
    public static void rainbowText(GuiGraphicsExtractor context, Font font, String text,
                                   float x, float y, float scale) {
        rainbowText(context, font, text, x, y, scale, 1f);
    }

    /** Scaled rainbow text with a fade. */
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

    /** Filled rectangle with a one pixel border. */
    public static void borderedRect(GuiGraphicsExtractor context, int x, int y, int x2, int y2,
                                    int fillColor, int borderColor) {
        context.fill(x, y, x2, y2, borderColor);
        context.fill(x + 1, y + 1, x2 - 1, y2 - 1, fillColor);
    }
}
