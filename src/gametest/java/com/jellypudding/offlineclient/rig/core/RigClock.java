package com.jellypudding.offlineclient.rig.core;

// How fast the test game ticks. One is the game's own pace of twenty ticks a second. A blanket
// run that only looks for exceptions hurries and a case that measures the game never does.
public final class RigClock {

    public static final float NORMAL = 1;

    private static volatile float speed = NORMAL;

    private RigClock() {
    }

    public static float speed() {
        return speed;
    }

    public static void set(float times) {
        speed = times;
    }
}
