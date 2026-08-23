package com.jellypudding.offlineclient.util;

public final class ColorUtil {

    private ColorUtil() {
    }

    // HSV to opaque ARGB. Hue is in degrees and wraps around.
    public static int hsv(float hue, float saturation, float value) {
        float h = ((hue % 360f) + 360f) % 360f;
        int rgb = java.awt.Color.HSBtoRGB(h / 360f, Math.clamp(saturation, 0f, 1f), Math.clamp(value, 0f, 1f));
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    // The offset shifts the phase between adjacent characters or entries.
    public static int rainbow(int offset) {
        float hue = ((System.currentTimeMillis() % 4000L) / 4000f * 360f + offset * 8f) % 360f;
        return hsv(hue, 0.65f, 1f);
    }

    public static int withAlpha(int color, int alpha) {
        return (Math.clamp(alpha, 0, 255) << 24) | (color & 0xFFFFFF);
    }

    // Multiplies the colour's own alpha by a 0 to 1 factor.
    public static int fade(int color, float alpha) {
        int a = (int) ((color >>> 24) * Math.clamp(alpha, 0f, 1f));
        return (a << 24) | (color & 0xFFFFFF);
    }

    // Perceived brightness from 0 to 1. Alpha is ignored.
    public static float luminance(int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    // Above this share of full health the ramp reads green.
    private static final float HEALTHY = 0.6f;

    // Above this share it reads amber. Below it reads red.
    private static final float HURT = 0.3f;

    // A green to amber to red ramp for a health fraction from 0 to 1.
    public static int health(float fraction) {
        if (fraction > HEALTHY) {
            return 0xFF40E060;
        }
        return fraction > HURT ? 0xFFFFC040 : 0xFFFF4040;
    }

    public static int lerp(int from, int to, float t) {
        t = Math.clamp(t, 0f, 1f);
        int a = (int) (((from >> 24) & 0xFF) + (((to >> 24) & 0xFF) - ((from >> 24) & 0xFF)) * t);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
