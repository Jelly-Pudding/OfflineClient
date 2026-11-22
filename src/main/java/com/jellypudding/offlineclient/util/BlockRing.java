package com.jellypudding.offlineclient.util;

// The blocks that make the skin of a ball one block thick. A level cut through the ball is a
// ring and the cut through its middle is the widest one. Offsets are measured from the block in
// the middle of the ball.
public final class BlockRing {

    @FunctionalInterface
    public interface Cell {
        void at(int dx, int dz);
    }

    // The skin runs half a block either side of the radius.
    private static final double HALF = 0.5;

    private BlockRing() {
    }

    public static boolean onBall(long dx, long dy, long dz, int radius) {
        double distance = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
        return distance >= radius - HALF && distance < radius + HALF;
    }

    // Only the stretch of each column the ring crosses inside the window is looked at. A ring of
    // any size costs no more than the part of it in the window.
    public static void slice(int radius, long dy, int minDx, int maxDx, int minDz, int maxDz, Cell out) {
        double outer = radius + HALF;
        double inner = Math.max(0, radius - HALF);
        // The squares a block's distance on the level has to stay between.
        double most = outer * outer - (double) dy * dy;
        double least = inner * inner - (double) dy * dy;
        if (most <= 0) {
            return;
        }
        int reach = (int) Math.ceil(Math.sqrt(most));
        for (int dx = Math.max(minDx, -reach); dx <= Math.min(maxDx, reach); dx++) {
            double room = most - (double) dx * dx;
            if (room <= 0) {
                continue;
            }
            double gap = least - (double) dx * dx;
            // One block of slack each end. The test has the last word.
            long near = gap <= 0 ? 0 : Math.max(0, (long) Math.floor(Math.sqrt(gap)) - 1);
            long far = (long) Math.ceil(Math.sqrt(room));
            // The south half takes the middle row and the north half mirrors the rest.
            column(radius, dx, dy, Math.max(near, minDz), Math.min(far, maxDz), 1, out);
            column(radius, dx, dy, Math.max(Math.max(near, 1), -(long) maxDz), Math.min(far, -(long) minDz), -1, out);
        }
    }

    private static void column(int radius, int dx, long dy, long from, long to, int sign, Cell out) {
        for (long dz = from; dz <= to; dz++) {
            if (onBall(dx, dy, dz, radius)) {
                out.at(dx, (int) (sign * dz));
            }
        }
    }
}
