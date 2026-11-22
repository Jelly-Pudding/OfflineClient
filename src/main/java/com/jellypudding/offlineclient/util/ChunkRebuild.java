package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;

// Asks the renderer to mesh every chunk again. A module whose settings feed
// the mesher waits for them to sit still for half a second first.
public final class ChunkRebuild {

    private static final int SETTLE_TICKS = 10;

    private int cooldown;

    public void tick(boolean changed) {
        if (changed) {
            cooldown = SETTLE_TICKS;
        } else if (cooldown > 0 && --cooldown == 0) {
            now();
        }
    }

    public static void now() {
        if (OfflineClient.MC.levelExtractor != null) {
            OfflineClient.MC.levelExtractor.allChanged();
        }
    }
}
