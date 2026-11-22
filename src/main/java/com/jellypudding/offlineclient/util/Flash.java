package com.jellypudding.offlineclient.util;

public final class Flash {

    public static final long SHOWN_MS = 2500;

    private static final long FADE_MS = 400;

    private Flash() {
    }

    // One until the last moments before the given time and down to nought by then.
    public static float strength(long until) {
        return Math.clamp((until - System.currentTimeMillis()) / (float) FADE_MS, 0f, 1f);
    }
}
