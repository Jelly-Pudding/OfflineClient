package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.lang.ref.WeakReference;

// Notices a fresh join to a server. Every join builds a new connection listener
// whilst a respawn or a dimension change keeps the one there is. A join counts once
// the player has loaded into the world. The listener last seen is held weakly.
public final class JoinWatch {

    private WeakReference<ClientPacketListener> seen = new WeakReference<>(null);

    // True once for each join.
    public boolean joined() {
        ClientPacketListener now = OfflineClient.MC.getConnection();
        if (now == null || !now.hasClientLoaded() || now == seen.get()) {
            return false;
        }
        seen = new WeakReference<>(now);
        return true;
    }

    // Takes the join the client is in as already seen.
    public void accept() {
        seen = new WeakReference<>(OfflineClient.MC.getConnection());
    }
}
