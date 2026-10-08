package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.util.Util;

import java.util.Arrays;

// The server sends the time every twenty ticks. The gaps between those give its tick rate
// and a gap that runs well past the usual one means the server has stalled.
public final class TickRate {

    public static final TickRate INSTANCE = new TickRate();

    private static final int TICKS_BETWEEN_PACKETS = 20;
    // A healthy server sends the time this often.
    private static final long BEAT_MILLIS = (long) TICKS_BETWEEN_PACKETS * SharedConstants.MILLIS_PER_TICK;
    // The rate and the usual gap are read over the last ten gaps.
    private static final int KEPT = 11;
    // How late the time may run before a module with no grace setting of its own holds off.
    private static final long STALL_MILLIS = 500;
    private static final double MILLIS_PER_SECOND = 1000;

    // Arrival times of the packets that came on the beat. The oldest is overwritten first.
    private final long[] arrivals = new long[KEPT];
    private int count;
    private int next;
    private long lastPacketAt;
    // Whether the server has sent the time since the measuring started again.
    private boolean heard;
    // The next time packet comes off the beat. It shows the server is alive but starts no gap.
    private boolean offBeat;
    private long usualGap = BEAT_MILLIS;
    private volatile float tps = SharedConstants.TICKS_PER_SECOND;
    // The gaps the rate was last worked out over and their total length.
    private int closedGaps;
    private long closedSpan;
    // A paused single player world sends nothing.
    private boolean paused;

    private TickRate() {
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        if (packet instanceof ClientboundLoginPacket || packet instanceof ClientboundStartConfigurationPacket) {
            reset();
        } else if (packet instanceof ClientboundRespawnPacket) {
            // The server sends the time again straight after a respawn.
            restart();
        } else if (packet instanceof ClientboundSetTimePacket) {
            sample();
        }
    }

    // A gap that spans a pause says nothing about the server. The time it sends as it wakes
    // can arrive before or after this tick sees the pause end.
    @Subscribe
    private synchronized void onClientTick(ClientTickEvent event) {
        boolean now = OfflineClient.MC.isPaused();
        if (now != paused) {
            restart();
        }
        paused = now;
    }

    private synchronized void reset() {
        restart();
        usualGap = BEAT_MILLIS;
        tps = SharedConstants.TICKS_PER_SECOND;
        closedGaps = 0;
        closedSpan = 0;
    }

    private synchronized void restart() {
        count = 0;
        next = 0;
        heard = false;
        offBeat = true;
    }

    private synchronized void sample() {
        long now = Util.getMillis();
        lastPacketAt = now;
        heard = true;
        if (offBeat) {
            offBeat = false;
            return;
        }
        arrivals[next] = now;
        next = (next + 1) % KEPT;
        count = Math.min(count + 1, KEPT);
        if (count < 2) {
            return;
        }
        long[] gaps = gaps();
        long span = Arrays.stream(gaps).sum();
        if (span > 0) {
            tps = rate(gaps.length, span);
            closedGaps = gaps.length;
            closedSpan = span;
        }
        // The middle gap ignores a long stall and the floor ignores the odd extra packet a
        // command sends. With an even count the shorter of the two middle gaps is taken.
        Arrays.sort(gaps);
        usualGap = Math.max(BEAT_MILLIS, gaps[(gaps.length - 1) / 2]);
    }

    // The gaps between the kept packets from oldest to newest.
    private long[] gaps() {
        int oldest = count < KEPT ? 0 : next;
        long[] gaps = new long[count - 1];
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = arrivals[(oldest + i + 1) % KEPT] - arrivals[(oldest + i) % KEPT];
        }
        return gaps;
    }

    // Ticks a second over this many gaps of twenty ticks that took this long in all.
    private static float rate(int gaps, long span) {
        return (float) Math.clamp(TICKS_BETWEEN_PACKETS * MILLIS_PER_SECOND * gaps / span,
            0, SharedConstants.TICKS_PER_SECOND);
    }

    // Ticks a second the server has managed lately. Twenty is a healthy server.
    public float tps() {
        return tps;
    }

    // The same rate with the gap still open counted in once it runs past the usual one. A
    // server that has stalled reads slow whilst it is still quiet.
    public synchronized float liveTps() {
        long open = millisSinceLastTick();
        return open <= usualGap ? tps : rate(closedGaps + 1, closedSpan + open);
    }

    // Milliseconds since the server last sent the time. Nought before it has and whilst paused.
    public synchronized long millisSinceLastTick() {
        return !heard || paused ? 0 : Util.getMillis() - lastPacketAt;
    }

    // How far the time runs past its usual gap. Below nought whilst it is not yet due.
    public synchronized long millisLate() {
        return millisSinceLastTick() - usualGap;
    }

    // True once the time is later than its usual gap by more than the given grace.
    public synchronized boolean lagging(long graceMillis) {
        return millisLate() > graceMillis;
    }

    // The fixed grace for modules that hold off on lag without a setting of their own.
    public boolean stalled() {
        return lagging(STALL_MILLIS);
    }
}
