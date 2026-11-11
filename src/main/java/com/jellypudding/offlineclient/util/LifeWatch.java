package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.player.LocalPlayer;

import java.lang.ref.WeakReference;

// Notices the client handing out a fresh body. A respawn and a rejoin and a dimension change
// each bring one. The body seen last is held weakly and never keeps its world alive.
public final class LifeWatch {

    private WeakReference<LocalPlayer> seen = new WeakReference<>(null);

    // True once for each fresh body.
    public boolean changed() {
        LocalPlayer now = OfflineClient.MC.player;
        if (now == seen.get()) {
            return false;
        }
        seen = new WeakReference<>(now);
        return true;
    }
}
