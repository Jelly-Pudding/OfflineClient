package com.jellypudding.offlineclient.util;

public final class ColorUtil {

    private ColorUtil() {
    }

    // HSV to opaque ARGB. Hue is in degrees and wraps around.
    public static int hsv(float hue, float saturation, float value) {
        float h = (((hue % 360f) + 360f) % 360f) / 60f;
        float s = Math.clamp(saturation, 0f, 1f);
        float v = Math.clamp(value, 0f, 1f);
        int sector = (int) h;
        float f = h - sector;
        float p = v * (1 - s);
        float q = v * (1 - s * f);
        float t = v * (1 - s * (1 - f));
        float r;
        float g;
        float b;
        switch (sector % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return 0xFF000000 | channel(r) << 16 | channel(g) << 8 | channel(b);
    }

    private static int channel(float share) {
        return Math.clamp(Math.round(share * 255f), 0, 255);
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

    // Green under a hundred milliseconds. Amber under two hundred and fifty. Red beyond.
    public static int ping(int latency) {
        if (latency < 0) {
            return 0xFF909090;
        }
        return latency < 100 ? 0xFF50FF50 : latency < 250 ? 0xFFFFD040 : 0xFFFF5050;
    }

    // A shade of one colour moved onto another. The result keeps how much lighter or
    // duller the shade is than the base. A whole palette can be recoloured at once.
    public static int retint(int shade, int base, int wanted) {
        if ((base & 0xFFFFFF) == (wanted & 0xFFFFFF)) {
            return shade;
        }
        float[] one = hsvOf(shade);
        float[] from = hsvOf(base);
        float[] to = hsvOf(wanted);
        float saturation = from[1] <= 0 ? to[1] : to[1] * (one[1] / from[1]);
        float value = from[2] <= 0 ? to[2] : to[2] * (one[2] / from[2]);
        return withAlpha(hsv(to[0], Math.clamp(saturation, 0f, 1f), Math.clamp(value, 0f, 1f)),
            shade >>> 24);
    }

    // Hue in degrees then saturation then value each from nought to one.
    public static float[] hsvOf(int color) {
        float red = (color >> 16 & 0xFF) / 255f;
        float green = (color >> 8 & 0xFF) / 255f;
        float blue = (color & 0xFF) / 255f;
        float max = Math.max(red, Math.max(green, blue));
        float min = Math.min(red, Math.min(green, blue));
        float spread = max - min;
        float hue = 0;
        if (spread > 0) {
            if (max == red) {
                hue = 60 * (((green - blue) / spread) % 6);
            } else if (max == green) {
                hue = 60 * ((blue - red) / spread + 2);
            } else {
                hue = 60 * ((red - green) / spread + 4);
            }
        }
        return new float[] {(hue + 360) % 360, max <= 0 ? 0 : spread / max, max};
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
