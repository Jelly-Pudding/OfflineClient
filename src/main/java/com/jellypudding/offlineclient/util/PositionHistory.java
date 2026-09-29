package com.jellypudding.offlineclient.util;

import net.minecraft.world.phys.Vec3;

// Where you stood over the last few seconds. A packet that measures something from
// you was worked out where the server last saw you and that was a round trip ago.
public final class PositionHistory {

    // Five seconds of ticks covers any ping worth playing on.
    private static final int KEPT = 100;

    // Further than this in one tick is a teleport. The server learnt the new spot at
    // once and the spots before it tell nothing.
    private static final double JUMP = 16;

    // The round trip the tab list gives is an average the server sends every half minute and
    // a packet can wait a tick at either end. The true delay can be this many ticks either way.
    private static final int ROUND_TRIP_DOUBT = 3;

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

    // Where the server saw you when it built a packet that arrives this tick. That was
    // about one round trip ago.
    public Vec3 asServerSaw() {
        return ticksAgo(ServerInfo.pingTicks());
    }

    // How far asServerSaw may be from the truth. The farthest you stood from it within the
    // doubt of the round trip. Nought before any record.
    public double serverSawSlack() {
        int back = ServerInfo.pingTicks();
        Vec3 at = ticksAgo(back);
        if (at == null) {
            return 0;
        }
        double farthest = 0;
        for (int doubt = -ROUND_TRIP_DOUBT; doubt <= ROUND_TRIP_DOUBT; doubt++) {
            farthest = Math.max(farthest, at.distanceTo(ticksAgo(back + doubt)));
        }
        return farthest;
    }

    // True when you stood within reach of the spot at any point in the last few ticks.
    public boolean wasNear(Vec3 spot, double reach, int ticks) {
        for (int back = 0; back < Math.min(ticks, size); back++) {
            if (ticksAgo(back).distanceTo(spot) <= reach) {
                return true;
            }
        }
        return false;
    }
}
