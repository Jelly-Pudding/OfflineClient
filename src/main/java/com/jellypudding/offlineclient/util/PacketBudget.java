package com.jellypudding.offlineclient.util;

import net.minecraft.SharedConstants;

import java.util.concurrent.TimeUnit;

// Counts every packet the client sent over the last seven seconds and how many of them
// were bursts a module sent on top of plain play. Paper drops a client whose average over
// that window reaches five hundred a second. Packets are sent from more than one thread.
public final class PacketBudget {

    // Paper's window held in slices of one tick.
    private static final long SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(SharedConstants.MILLIS_PER_TICK);
    private static final int SLICES = 140;
    private static final float WINDOW_SECONDS = SLICES / (float) SharedConstants.TICKS_PER_SECOND;

    // What Paper allows in its window.
    private static final int PAPER = 3500;

    // Half of that. The modules that send bursts share this much between them.
    public static final int CEILING = PAPER / 2;

    // Four fifths of it. Plain play sped up for good may fill this much.
    private static final int STEADY = PAPER * 4 / 5;

    // Nine tenths of it. Bursts and plain play together stop here.
    private static final int TOTAL = PAPER * 9 / 10;

    public static final float SUSTAINED = STEADY / WINDOW_SECONDS;

    private final long[] stamps = new long[SLICES];
    private final int[] counts = new int[SLICES];
    private final int[] bursts = new int[SLICES];

    public synchronized void record(boolean burst) {
        long slice = System.nanoTime() / SLICE_NANOS;
        int index = Math.floorMod(slice, SLICES);
        if (stamps[index] != slice) {
            stamps[index] = slice;
            counts[index] = 0;
            bursts[index] = 0;
        }
        counts[index]++;
        if (burst) {
            bursts[index]++;
        }
    }

    // How many more packets a burst may add. Bursts have a ceiling of their own and stop
    // where everything sent together reaches the total.
    public synchronized int spare() {
        return Math.max(0, Math.min(CEILING - inWindow(bursts), TOTAL - inWindow(counts)));
    }

    // Packets a second plain play may keep up for as long as it likes. What bursts have
    // spent in the window comes off its share.
    public synchronized float sustained() {
        return Math.max(0, STEADY - inWindow(bursts)) / WINDOW_SECONDS;
    }

    private int inWindow(int[] slices) {
        long slice = System.nanoTime() / SLICE_NANOS;
        int sent = 0;
        for (int i = 0; i < SLICES; i++) {
            if (slice - stamps[i] < SLICES) {
                sent += slices[i];
            }
        }
        return sent;
    }
}
