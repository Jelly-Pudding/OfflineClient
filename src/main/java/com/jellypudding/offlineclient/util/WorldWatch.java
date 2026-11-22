package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;

// Notices the client moving to a new world object. A rejoin and a dimension change
// both hand out a fresh one. The old world is held weakly and never kept alive.
public final class WorldWatch {

    private WeakReference<Level> seen = new WeakReference<>(null);

    // True once for each new world.
    public boolean changed() {
        Level now = OfflineClient.MC.level;
        if (now == seen.get()) {
            return false;
        }
        seen = new WeakReference<>(now);
        return true;
    }

    public void accept() {
        seen = new WeakReference<>(OfflineClient.MC.level);
    }

    public void forget() {
        seen = new WeakReference<>(null);
    }
}
