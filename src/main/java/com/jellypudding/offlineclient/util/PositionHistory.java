package com.jellypudding.offlineclient.util;

import net.minecraft.world.phys.Vec3;

// Where you stood over the last few seconds. A packet that measures something from
// you was worked out where the server last saw you and that was a round trip ago.
public final class PositionHistory {

    private static final int TICK_MS = 50;

    // Five seconds of ticks covers any ping worth playing on.
    private static final int KEPT = 100;

    // Further than this in one tick is a teleport. The server knew the new spot at
    // once and every older spot is no answer any more.
    private static final double JUMP = 16;

    private final Vec3[] ring = new Vec3[KEPT];
    private int next;
    private int size;

    // Once a tick with where you stand.
    public void record(Vec3 position) {
        Vec3 last = ticksAgo(0);
        if (last != null && last.distanceTo(position) > JUMP) {
            clear();
        }
        ring[next] = position;
        next = (next + 1) % KEPT;
        size = Math.min(size + 1, KEPT);
    }

    public void clear() {
        size = 0;
    }

    // Null until something has been recorded. Asking further back than is kept
    // gives the oldest spot.
    public Vec3 ticksAgo(int ticks) {
        if (size == 0) {
            return null;
        }
        int back = Math.clamp(ticks, 0, size - 1);
        return ring[Math.floorMod(next - 1 - back, KEPT)];
    }

    // Where the server saw you when it sent a packet that arrives this tick. That is
    // your ping plus the tick the server waits before it handles a move.
    public Vec3 asServerSaw() {
        return ticksAgo(Math.round((ServerInfo.ping() + TICK_MS) / (float) TICK_MS));
    }
}
