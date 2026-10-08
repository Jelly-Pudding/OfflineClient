package com.jellypudding.offlineclient.util;

// How long a result stays lit on the thing that was clicked and how it eases away.
public final class Flash {

    public static final long SHOWN_MS = 2500;

    // The last part of the time shown fades out over this long.
    private static final long FADE_MS = 400;

    private Flash() {
    }

    // One until the last moments before the given time and down to nought by then.
    public static float strength(long until) {
        return Math.clamp((until - System.currentTimeMillis()) / (float) FADE_MS, 0f, 1f);
    }
}
