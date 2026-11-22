package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.config.FindLog;
import net.minecraft.core.BlockPos;

// A place a hunting module keeps sharpening such as a wither spot or a stronghold. Its find and
// its waypoint follow it as it moves.
public final class TrackedPlace {

    // A waypoint of the same name and a number this much further off than the place is unsure of
    // already marks it. A rejoin or a toggle then moves that one instead of saving a second.
    private static final double SAME_PLACE = 8;

    private final RepostGate saved = RepostGate.forWaypoint();
    private FindLog.Find logged;
    private String waypoint;

    public void log(FindLog finds, String dimension, BlockPos spot, String kind, String detail, long now) {
        if (logged != null && logged.pos().equals(spot) && logged.detail().equals(detail)) {
            return;
        }
        if (logged != null && !logged.pos().equals(spot)) {
            finds.remove(logged);
        }
        finds.add(dimension, spot, kind, detail);
        logged = new FindLog.Find(dimension, spot, kind, detail, now);
    }

    public void save(Leak leak, String stem, String anyDimension, double radius, long now) {
        if (!saved.due(leak.pos().getX(), leak.pos().getZ(), radius, now)) {
            return;
        }
        if (waypoint == null) {
            waypoint = leak.numberedNear(stem, anyDimension, radius + SAME_PLACE);
        }
        if (waypoint == null) {
            waypoint = leak.saveFresh(stem, anyDimension);
        } else {
            leak.saveAs(waypoint, anyDimension);
        }
    }

    public void saveAs(Leak leak, String name, String anyDimension, double radius, long now) {
        if (saved.due(leak.pos().getX(), leak.pos().getZ(), radius, now)) {
            leak.saveAs(name, anyDimension);
        }
    }

    public void resave() {
        saved.reset();
    }
}
