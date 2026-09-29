package com.jellypudding.offlineclient.util;

// Counts the packets the client sent over the last seven seconds. Paper drops a client
// whose average over that window reaches five hundred a second. Packets are sent from
// more than one thread.
public final class PacketBudget {

    // Paper's window held in slices of one tick.
    private static final long SLICE_NANOS = 50_000_000L;
    private static final int SLICES = 140;

    // Half of the 3500 packets Paper allows in its window. Normal play needs a few hundred.
    public static final int CEILING = 1750;

    private final long[] stamps = new long[SLICES];
    private final int[] counts = new int[SLICES];

    public synchronized void record() {
        long slice = System.nanoTime() / SLICE_NANOS;
        int index = Math.floorMod(slice, SLICES);
        if (stamps[index] != slice) {
            stamps[index] = slice;
            counts[index] = 0;
        }
        counts[index]++;
    }

    // How many more packets fit under the ceiling in the window.
    public synchronized int spare() {
        long slice = System.nanoTime() / SLICE_NANOS;
        int sent = 0;
        for (int i = 0; i < SLICES; i++) {
            if (slice - stamps[i] < SLICES) {
                sent += counts[i];
            }
        }
        return Math.max(0, CEILING - sent);
    }
}
