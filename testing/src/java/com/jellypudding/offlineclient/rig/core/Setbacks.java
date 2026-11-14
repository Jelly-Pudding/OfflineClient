package com.jellypudding.offlineclient.rig.core;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;

import java.util.concurrent.atomic.AtomicInteger;

// Counts how often the server moved the test player. A server that does not believe a move puts
// the player back where it last believed it and that reaches the game as one of these packets.
final class Setbacks {

    private static final AtomicInteger COUNT = new AtomicInteger();
    private static boolean listening;

    // Heard on the network thread.
    private static final Object LISTENER = new Object() {
        @Subscribe
        private void onPacket(PacketReceiveEvent event) {
            if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
                COUNT.incrementAndGet();
            }
        }
    };

    private Setbacks() {
    }

    static void install() {
        if (!listening) {
            OfflineClient.INSTANCE.getEventBus().register(LISTENER);
            listening = true;
        }
    }

    static int count() {
        return COUNT.get();
    }
}
