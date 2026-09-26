package com.jellypudding.offlineclient.util;

import java.util.List;

// Where bearings taken from different places cross. Each bearing misses the true spot
// by a few blocks from where you really stood and from the player shuffling about.
// The radius says how far off the crossing may be.
public record BearingFix(double x, double z, double radius) {

    // Blocks a single bearing is expected to miss by.
    private static final double MISS = 2.5;

    // A bearing that misses the crossing by more than this means the player moved.
    private static final double MOVED = 32;

    // The server only sends a bearing past 332 blocks. A crossing much nearer than that
    // to where a line was taken is wrong.
    private static final double NEAREST = 128;

    // Two standard errors along the least certain direction.
    private static final double SURE = 2;

    // Below this the lines run parallel for every practical purpose.
    private static final double PARALLEL = 1.0E-9;

    private static final double FULL_TURN = 2 * Math.PI;

    // The least squares crossing with what it takes to judge it.
    private record Crossing(double x, double z, double weakest, double squares, double worst,
                            boolean inFront) {
    }

    // Null unless the bearings spread at least this many radians and meet in front of
    // every place they were taken from.
    public static BearingFix cross(List<Bearing> bearings, double minSpread) {
        if (bearings.size() < 2 || spread(bearings) < minSpread) {
            return null;
        }
        Crossing crossing = solve(bearings);
        if (crossing == null || !crossing.inFront()) {
            return null;
        }
        double variance = MISS * MISS;
        int spare = bearings.size() - 2;
        if (spare > 0) {
            variance = Math.max(variance, crossing.squares() / spare);
        }
        return new BearingFix(crossing.x(), crossing.z(),
            SURE * Math.sqrt(variance / crossing.weakest()));
    }

    // False when the bearings cannot all point at one spot. Lines that are nearly
    // parallel can only be judged on how far they miss.
    public static boolean agree(List<Bearing> bearings, double minSpread) {
        Crossing crossing = solve(bearings);
        if (crossing == null) {
            return true;
        }
        if (crossing.worst() > MOVED) {
            return false;
        }
        return crossing.inFront() || spread(bearings) < minSpread;
    }

    // The widest angle between any two of the bearings in radians.
    public static double spread(List<Bearing> bearings) {
        double first = bearings.getFirst().yaw();
        double low = 0;
        double high = 0;
        for (Bearing bearing : bearings) {
            double turn = Math.IEEEremainder(bearing.yaw() - first, FULL_TURN);
            low = Math.min(low, turn);
            high = Math.max(high, turn);
        }
        return high - low;
    }

    // True when a fresh bearing cannot point at this spot. The player has moved on.
    public boolean missedBy(Bearing bearing) {
        return Math.abs(bearing.offset(x, z)) > MOVED + radius || bearing.ahead(x, z) < NEAREST;
    }

    private static Crossing solve(List<Bearing> bearings) {
        // Each line is its normal and its distance from the origin. The point closest to
        // every line solves a two by two system built from their sums.
        double xx = 0;
        double xz = 0;
        double zz = 0;
        double bx = 0;
        double bz = 0;
        for (Bearing bearing : bearings) {
            double nx = Math.cos(bearing.yaw());
            double nz = Math.sin(bearing.yaw());
            double reach = nx * bearing.x() + nz * bearing.z();
            xx += nx * nx;
            xz += nx * nz;
            zz += nz * nz;
            bx += nx * reach;
            bz += nz * reach;
        }
        double det = xx * zz - xz * xz;
        if (det < PARALLEL) {
            return null;
        }
        double x = (zz * bx - xz * bz) / det;
        double z = (xx * bz - xz * bx) / det;
        double squares = 0;
        double worst = 0;
        boolean inFront = true;
        for (Bearing bearing : bearings) {
            double miss = bearing.offset(x, z);
            squares += miss * miss;
            worst = Math.max(worst, Math.abs(miss));
            inFront &= bearing.ahead(x, z) >= NEAREST;
        }
        // The smaller eigenvalue in the form that keeps its precision when it is tiny.
        double trace = xx + zz;
        double weakest = 2 * det / (trace + Math.sqrt(Math.max(0, trace * trace - 4 * det)));
        return new Crossing(x, z, weakest, squares, worst, inFront);
    }
}
