package com.jellypudding.offlineclient.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Bearings towards one thing taken from the places you stood. It keeps the lines that can
// all point at one spot and crosses them into a fix. A thing that can move drops the
// earlier lines once a new one cannot point where they do.
public final class BearingTrail {

    // Enough for a long walk sideways to something far off.
    private static final int MAX_BEARINGS = 64;

    // A reading of something that stays put taken this close to the last one replaces it. A
    // moving thing is read each time its line turns half a degree and each reading counts.
    private static final double SAME_SPOT = 4;

    // A new yaw whilst you stand this still can only come from the thing moving. Past 332
    // blocks your own step turns the line less than the half degree the server waits for.
    private static final double STILL = 2;

    private final BearingFix.Tolerance tolerance;
    private final boolean moves;
    // Oldest first. Something that stays put keeps one reading for each spot you stood on.
    private final List<Bearing> bearings = new ArrayList<>();
    private BearingFix fix;
    // The spread the fix was worked out for. Not a number once the lines change.
    private double fixedFor = Double.NaN;

    public BearingTrail(BearingFix.Tolerance tolerance, boolean moves) {
        this.tolerance = tolerance;
        this.moves = moves;
    }

    public void add(Bearing bearing, double minSpread) {
        join(bearings, bearing, minSpread);
        fixedFor = Double.NaN;
    }

    // The fix the trail would give once the bearing joined it. The trail stays as it is.
    public BearingFix fixWith(Bearing bearing, double minSpread) {
        List<Bearing> joined = new ArrayList<>(bearings);
        join(joined, bearing, minSpread);
        return BearingFix.cross(joined, minSpread, tolerance);
    }

    // Adds the bearing to a copy of this trail's lines or to the lines themselves. A reading
    // that cannot point at a well founded fix means the thing moved on. Any new reading of a
    // moving thing whilst you stand still means the same.
    private void join(List<Bearing> lines, Bearing bearing, double minSpread) {
        BearingFix known = fix(minSpread);
        if ((moves && !lines.isEmpty() && lines.getLast().originDistance(bearing) < STILL)
            || (known != null && lines.size() > 2 && known.missedBy(bearing, tolerance))) {
            lines.clear();
        }
        if (!moves && !lines.isEmpty() && lines.getLast().originDistance(bearing) < SAME_SPOT) {
            lines.removeLast();
        }
        lines.add(bearing);
        if (lines.size() > MAX_BEARINGS) {
            lines.removeFirst();
        }
        while (lines.size() > 1 && !BearingFix.agree(lines, minSpread, tolerance)) {
            lines.removeFirst();
        }
    }

    // True when the bearing could point where every line of this trail does.
    public boolean fits(Bearing bearing, double minSpread) {
        BearingFix known = fix(minSpread);
        if (known != null) {
            return !known.missedBy(bearing, tolerance);
        }
        List<Bearing> joined = new ArrayList<>(bearings);
        joined.add(bearing);
        return BearingFix.agree(joined, minSpread, tolerance);
    }

    // True when every line of this trail could point at the spot. An empty trail points anywhere.
    public boolean pointsAt(double x, double z) {
        BearingFix spot = new BearingFix(x, z, 0);
        for (Bearing bearing : bearings) {
            if (spot.missedBy(bearing, tolerance)) {
                return false;
            }
        }
        return true;
    }

    // Null until the lines spread this many radians and meet in front of every place they
    // were taken from. Worked out again only when the lines or the spread change.
    public BearingFix fix(double minSpread) {
        if (minSpread != fixedFor) {
            fix = BearingFix.cross(bearings, minSpread, tolerance);
            fixedFor = minSpread;
        }
        return fix;
    }

    // Oldest first.
    public List<Bearing> bearings() {
        return Collections.unmodifiableList(bearings);
    }

    public boolean isEmpty() {
        return bearings.isEmpty();
    }

    // Null whilst the trail is empty.
    public Bearing newest() {
        return bearings.isEmpty() ? null : bearings.getLast();
    }

    public void clear() {
        bearings.clear();
        fixedFor = Double.NaN;
    }
}
