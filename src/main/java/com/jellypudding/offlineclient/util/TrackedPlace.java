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
    // Null until the place is in the finds.
    private FindLog.Find logged;
    // Null until the place is saved as a waypoint.
    private String waypoint;

    // The find moves with the place and takes its new words. The find it leaves behind goes.
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

    // Saves the place as a waypoint named after the stem and a number such as Wither2 and moves
    // it once the place strays.
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

    // Saves the place as the named waypoint and moves it once the place strays.
    public void saveAs(Leak leak, String name, String anyDimension, double radius, long now) {
        if (saved.due(leak.pos().getX(), leak.pos().getZ(), radius, now)) {
            leak.saveAs(name, anyDimension);
        }
    }

    // The next save goes through wherever the place is.
    public void resave() {
        saved.reset();
    }
}
