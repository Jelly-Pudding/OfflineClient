package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;

import java.util.concurrent.atomic.AtomicInteger;

// Counts the times the server pulls the player back. The packet arrives on the netty
// thread and every watcher reads the count from the game thread.
public enum Lagback {
    INSTANCE;

    private static final AtomicInteger playerPulls = new AtomicInteger();
    // The server only sends a vehicle its place when it refuses a move the rider sent.
    private static final AtomicInteger vehiclePulls = new AtomicInteger();

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            playerPulls.incrementAndGet();
        } else if (event.getPacket() instanceof ClientboundMoveVehiclePacket) {
            vehiclePulls.incrementAndGet();
        }
    }

    // Each module keeps its own and sees every pull back once.
    public static final class Watcher {

        private final boolean vehicles;
        private int seen;

        public Watcher() {
            this(false);
        }

        private Watcher(boolean vehicles) {
            this.vehicles = vehicles;
            seen = pulls();
        }

        // Also sees the vehicle the player steers pulled back.
        public static Watcher withVehicles() {
            return new Watcher(true);
        }

        // True once when the server has pulled the player back since the last call.
        public boolean happened() {
            int now = pulls();
            boolean fresh = now != seen;
            seen = now;
            return fresh;
        }

        // Forgets any pull back that came whilst the module was off.
        public void sync() {
            seen = pulls();
        }

        private int pulls() {
            return playerPulls.get() + (vehicles ? vehiclePulls.get() : 0);
        }
    }
}
