package com.jellypudding.offlineclient.util;

// The numbers the game moves a loosed projectile by on every tick of its flight.
public final class ProjectileUtil {

    // The share of its speed an arrow keeps each tick in open air.
    public static final double ARROW_DRAG = 0.99;

    // Blocks per tick an arrow loses downward.
    public static final double ARROW_GRAVITY = 0.05;

    // Blocks per tick a thrown pearl or snowball loses downward.
    public static final double THROWN_GRAVITY = 0.03;

    private ProjectileUtil() {
    }
}
