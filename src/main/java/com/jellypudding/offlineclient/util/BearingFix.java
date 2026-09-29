package com.jellypudding.offlineclient.util;

import java.util.List;

// Where bearings taken from different places cross. Each bearing misses the true spot
// by a little from where you really stood and from the thing itself wandering. A line
// with a blurred yaw misses by more the further out the crossing lies and counts for
// less. The radius says how far off the crossing may be.
public record BearingFix(double x, double z, double radius) {

    // How the lines to one thing miss it. Each misses by about spot blocks on top of its
    // own blur carried out to the crossing. One that misses by more than lost blocks and
    // its blur points at something else.
    public record Tolerance(double spot, double lost) {
    }

    // How many of its own blurs a line may be out by before it points at something else.
    private static final double LOST_BLURS = 4;

    // Two standard errors along the least certain direction.
    private static final double SURE = 2;

    // Lines this close to parallel give no crossing. It is measured against how much they
    // weigh in all.
    private static final double PARALLEL = 1.0E-9;

    // Each pass weighs the lines by how far out the last crossing lies. Three settle it.
    private static final int PASSES = 3;

    // Gauss Newton steps after the passes. They stop once a step moves less than a
    // hundredth of a block.
    private static final int SETTLE_STEPS = 8;
    private static final double SETTLED = 0.01;

    private static final double FULL_TURN = 2 * Math.PI;

    // The weighted least squares crossing with what it takes to judge it.
    private record Crossing(double x, double z, double weakest, double chiSquare, boolean lost,
                            boolean inFront) {
    }

    // Every line's scaled miss at one point and how fast it grows as the point moves. The
    // matrix of those slopes steps the point and its smaller eigenvalue sizes the radius.
    private static final class Sums {
        private double xx;
        private double xz;
        private double zz;
        private double gx;
        private double gz;
        private double chiSquare;
        private boolean lost;
        private boolean inFront = true;

        private Sums(List<Bearing> bearings, Tolerance tolerance, double x, double z) {
            for (Bearing bearing : bearings) {
                add(bearing, tolerance, x, z);
            }
        }

        // A line's miss over the miss it is expected to have at that range. The expected
        // miss grows with the range whilst the point lies past the least distance.
        private void add(Bearing bearing, Tolerance tolerance, double x, double z) {
            double miss = bearing.offset(x, z);
            double ahead = bearing.ahead(x, z);
            double range = Math.max(ahead, bearing.beyond());
            double expected = missAt(bearing, range, tolerance);
            double growth = ahead > bearing.beyond() ? range * bearing.blur() * bearing.blur() / expected : 0;
            double pull = miss / (expected * expected) * growth;
            double sx = Math.cos(bearing.yaw()) / expected - pull * bearing.dirX();
            double sz = Math.sin(bearing.yaw()) / expected - pull * bearing.dirZ();
            double scaled = miss / expected;
            xx += sx * sx;
            xz += sx * sz;
            zz += sz * sz;
            gx += sx * scaled;
            gz += sz * scaled;
            chiSquare += scaled * scaled;
            lost |= Math.abs(miss) > lostAt(bearing, ahead, tolerance);
            inFront &= ahead >= bearing.beyond();
        }

        private double det() {
            return xx * zz - xz * xz;
        }

        private boolean parallel() {
            return det() < PARALLEL * (xx + zz) * (xx + zz);
        }

        // The smaller eigenvalue in the form that keeps its precision when it is tiny.
        private double weakest() {
            double trace = xx + zz;
            return 2 * det() / (trace + Math.sqrt(Math.max(0, trace * trace - 4 * det())));
        }
    }

    // Null unless the bearings spread at least this many radians and meet in front of
    // every place they were taken from.
    public static BearingFix cross(List<Bearing> bearings, double minSpread, Tolerance tolerance) {
        if (bearings.size() < 2 || spread(bearings) < minSpread) {
            return null;
        }
        Crossing crossing = solve(bearings, tolerance);
        if (crossing == null || !crossing.inFront()) {
            return null;
        }
        // Lines that scatter more than expected widen the radius to match.
        int spare = bearings.size() - 2;
        double scatter = spare > 0 ? Math.max(1, crossing.chiSquare() / spare) : 1;
        return new BearingFix(crossing.x(), crossing.z(), SURE * Math.sqrt(scatter / crossing.weakest()));
    }

    // False when the bearings cannot all point at one spot. Lines that are nearly
    // parallel can only be judged on how far they miss.
    public static boolean agree(List<Bearing> bearings, double minSpread, Tolerance tolerance) {
        Crossing crossing = solve(bearings, tolerance);
        if (crossing == null) {
            return true;
        }
        if (crossing.lost()) {
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

    // True when a fresh bearing cannot point at this spot. The thing has moved on.
    public boolean missedBy(Bearing bearing, Tolerance tolerance) {
        double ahead = bearing.ahead(x, z);
        return ahead < bearing.beyond()
            || Math.abs(bearing.offset(x, z)) > lostAt(bearing, ahead, tolerance) + radius;
    }

    // How far a line may miss at that range and still point at the same thing.
    private static double lostAt(Bearing bearing, double range, Tolerance tolerance) {
        return tolerance.lost() + LOST_BLURS * Math.max(range, bearing.beyond()) * bearing.blur();
    }

    // How far a line is expected to miss at that range.
    private static double missAt(Bearing bearing, double range, Tolerance tolerance) {
        return Math.hypot(tolerance.spot(), Math.max(range, bearing.beyond()) * bearing.blur());
    }

    // The first pass takes every line as reaching no further than it must. Blurred lines
    // then need the crossing settled against misses that grow with the range.
    private static Crossing solve(List<Bearing> bearings, Tolerance tolerance) {
        Crossing crossing = null;
        for (int pass = 0; pass < PASSES; pass++) {
            Crossing next = weighed(bearings, tolerance, crossing);
            if (next == null) {
                return crossing;
            }
            crossing = next;
        }
        return crossing != null && blurred(bearings) ? settle(bearings, tolerance, crossing) : crossing;
    }

    private static boolean blurred(List<Bearing> bearings) {
        for (Bearing bearing : bearings) {
            if (bearing.blur() > 0) {
                return true;
            }
        }
        return false;
    }

    // Each line is its normal and its distance from the origin. The point closest to every
    // line solves a two by two system built from their sums weighed by the last crossing.
    private static Crossing weighed(List<Bearing> bearings, Tolerance tolerance, Crossing guess) {
        double xx = 0;
        double xz = 0;
        double zz = 0;
        double bx = 0;
        double bz = 0;
        for (Bearing bearing : bearings) {
            double range = guess == null ? bearing.beyond() : bearing.ahead(guess.x(), guess.z());
            double miss = missAt(bearing, range, tolerance);
            double weight = 1 / (miss * miss);
            double nx = Math.cos(bearing.yaw());
            double nz = Math.sin(bearing.yaw());
            double reach = nx * bearing.x() + nz * bearing.z();
            xx += weight * nx * nx;
            xz += weight * nx * nz;
            zz += weight * nz * nz;
            bx += weight * nx * reach;
            bz += weight * nz * reach;
        }
        double det = xx * zz - xz * xz;
        if (det < PARALLEL * (xx + zz) * (xx + zz)) {
            return null;
        }
        return judge(bearings, tolerance, (zz * bx - xz * bz) / det, (xx * bz - xz * bx) / det);
    }

    // Weighing the lines by the last crossing pulls it towards where the lines were taken
    // because a crossing further out forgives every miss more. Gauss Newton steps on the
    // scaled misses find the crossing that fits best.
    private static Crossing settle(List<Bearing> bearings, Tolerance tolerance, Crossing start) {
        double x = start.x();
        double z = start.z();
        for (int step = 0; step < SETTLE_STEPS; step++) {
            Sums sums = new Sums(bearings, tolerance, x, z);
            if (sums.parallel()) {
                return start;
            }
            double dx = (sums.xz * sums.gz - sums.zz * sums.gx) / sums.det();
            double dz = (sums.xz * sums.gx - sums.xx * sums.gz) / sums.det();
            x += dx;
            z += dz;
            if (Math.hypot(dx, dz) < SETTLED) {
                break;
            }
        }
        return judge(bearings, tolerance, x, z);
    }

    private static Crossing judge(List<Bearing> bearings, Tolerance tolerance, double x, double z) {
        Sums sums = new Sums(bearings, tolerance, x, z);
        return new Crossing(x, z, sums.weakest(), sums.chiSquare, sums.lost, sums.inFront);
    }
}
