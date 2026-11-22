package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;

// Holds the use key for a module that is eating or drinking. The hand takes a
// moment to pick the item up and a use that never starts is written off rather than held.
public final class UseHold {

    private static final int START_TIMEOUT = 20;

    private static final Minecraft MC = OfflineClient.MC;

    private boolean started;
    private int waited;

    public void begin() {
        started = false;
        waited = 0;
    }

    // False once the use has finished or been abandoned. The game starts another use the tick after
    // one ends for as long as the key is down and it does that before any module hears of the tick.
    // The vanilla wait between two uses is kept full whilst this one runs and the key is let go
    // before another can start.
    public boolean tick() {
        if (MC.player.isUsingItem()) {
            started = true;
            MC.rightClickDelay = InputUtil.USE_DELAY;
        } else if (started || ++waited > START_TIMEOUT) {
            return false;
        }
        InputUtil.holdUse();
        return true;
    }

    public void release() {
        InputUtil.releaseUse();
        begin();
    }
}
