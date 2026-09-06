package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;

// The server sends the time every twenty ticks. The gaps between those give its tick rate.
public final class TickRate {

    public static final TickRate INSTANCE = new TickRate();

    private static final int TICKS_BETWEEN_PACKETS = 20;
    private static final int SAMPLES = 10;

    private final float[] samples = new float[SAMPLES];
    private int filled;
    private int next;
    private volatile long lastPacketAt;
    private volatile float tps = 20;

    private TickRate() {
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundLoginPacket) {
            reset();
        } else if (event.getPacket() instanceof ClientboundSetTimePacket) {
            sample();
        }
    }

    private synchronized void reset() {
        filled = 0;
        next = 0;
        lastPacketAt = 0;
        tps = 20;
    }

    private synchronized void sample() {
        long now = System.currentTimeMillis();
        if (lastPacketAt != 0) {
            float seconds = (now - lastPacketAt) / 1000f;
            samples[next] = Math.clamp(TICKS_BETWEEN_PACKETS / seconds, 0, 20);
            next = (next + 1) % SAMPLES;
            filled = Math.min(filled + 1, SAMPLES);
            float total = 0;
            for (int i = 0; i < filled; i++) {
                total += samples[i];
            }
            tps = total / filled;
        }
        lastPacketAt = now;
    }

    // Ticks a second the server has managed lately. Twenty is a healthy server.
    public float tps() {
        return tps;
    }

    // Milliseconds since the server last showed signs of life.
    public long millisSinceLastTick() {
        long at = lastPacketAt;
        return at == 0 ? 0 : System.currentTimeMillis() - at;
    }

    // True whilst the server has gone quiet for longer than the given time.
    public boolean lagging(long millis) {
        return millisSinceLastTick() > millis;
    }
}
