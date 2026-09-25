package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;

import java.util.concurrent.atomic.AtomicInteger;

// Counts the times the server pulls the player back. The packet arrives on the netty
// thread and every watcher reads the count from the game thread.
public enum Lagback {
    INSTANCE;

    private static final AtomicInteger pulls = new AtomicInteger();

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            pulls.incrementAndGet();
        }
    }

    // Each module keeps its own and sees every pull back once.
    public static final class Watcher {

        private int seen = pulls.get();

        // True once when the server has pulled the player back since the last call.
        public boolean happened() {
            int now = pulls.get();
            boolean fresh = now != seen;
            seen = now;
            return fresh;
        }

        // Forgets any pull back that came whilst the module was off.
        public void sync() {
            seen = pulls.get();
        }
    }
}
