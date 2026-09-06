package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;

// Holds the use key for a module that is eating or drinking. The hand takes a
// moment to pick the item up and a use that never starts is written off rather than held.
public final class UseHold {

    // Ticks the hand is given to start before the attempt is abandoned.
    private static final int START_TIMEOUT = 20;

    private static final Minecraft MC = OfflineClient.MC;

    private boolean started;
    private int waited;

    // Called once as the use begins.
    public void begin() {
        started = false;
        waited = 0;
    }

    // True whilst the use is still running. False once it has finished or been abandoned.
    public boolean tick() {
        if (MC.player.isUsingItem()) {
            started = true;
        } else if (started || ++waited > START_TIMEOUT) {
            return false;
        }
        InputUtil.hold(MC.options.keyUse);
        return true;
    }

    // Lets the key go without lifting a finger really on it.
    public void release() {
        InputUtil.release(MC.options.keyUse);
        begin();
    }
}
