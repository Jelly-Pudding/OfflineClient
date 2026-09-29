package com.jellypudding.offlineclient.util;

import java.util.concurrent.TimeUnit;

// Says when a place that moves or sharpens is worth telling again. The first one always
// is. After that it must have moved further than the last one was sure of and further
// than a least distance and a while must have passed.
public final class RepostGate {

    // A place that moves less than this is a refinement and stays out of chat. One on the
    // move is posted once a minute at most.
    private static final double CHAT_MOVE = 64;
    private static final long CHAT_MS = TimeUnit.MINUTES.toMillis(1);

    // A waypoint moves once its place strays this far and this long has passed. Every move
    // writes the whole waypoint file.
    private static final double WAYPOINT_MOVE = 8;
    private static final long WAYPOINT_MS = TimeUnit.SECONDS.toMillis(30);

    private final double least;
    private final long waitMs;

    private boolean told;
    private double x;
    private double z;
    private double radius;
    private long at;

    private RepostGate(double least, long waitMs) {
        this.least = least;
        this.waitMs = waitMs;
    }

    public static RepostGate forChat() {
        return new RepostGate(CHAT_MOVE, CHAT_MS);
    }

    public static RepostGate forWaypoint() {
        return new RepostGate(WAYPOINT_MOVE, WAYPOINT_MS);
    }

    // True when the place is news. It then counts as told.
    public boolean due(double px, double pz, double pRadius, long now) {
        if (told && (now - at < waitMs || Math.hypot(px - x, pz - z) < Math.max(radius, least))) {
            return false;
        }
        told = true;
        x = px;
        z = pz;
        radius = pRadius;
        at = now;
        return true;
    }

    // The next place is news whatever it is.
    public void reset() {
        told = false;
    }
}
