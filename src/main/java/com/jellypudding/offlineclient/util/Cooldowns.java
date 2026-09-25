package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;

import java.util.HashMap;
import java.util.Map;

// Keys held back for a number of ticks on the player's clock. That clock starts again
// at zero on a respawn and every entry is dropped when it does.
public final class Cooldowns<K> {

    private final Map<K, Integer> until = new HashMap<>();
    private int lastTick;

    // Once a tick before anything is read. True when the clock went back and the
    // whole map was dropped.
    public boolean tick() {
        int now = clock();
        boolean restarted = now < lastTick;
        if (restarted) {
            until.clear();
        }
        lastTick = now;
        until.values().removeIf(end -> end <= now);
        return restarted;
    }

    public void put(K key, int ticks) {
        until.put(key, clock() + ticks);
    }

    public boolean contains(K key) {
        return until.containsKey(key);
    }

    public void clear() {
        until.clear();
        lastTick = 0;
    }

    private static int clock() {
        return OfflineClient.MC.player.tickCount;
    }
}
