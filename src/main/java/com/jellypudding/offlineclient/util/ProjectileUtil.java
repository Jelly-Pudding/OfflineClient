package com.jellypudding.offlineclient.util;

// The numbers the game moves a loosed projectile by on every tick of its flight.
public final class ProjectileUtil {

    // The share of its speed a projectile keeps each tick in open air.
    public static final double AIR_DRAG = 0.99;

    // Blocks per tick an arrow loses downward.
    public static final double ARROW_GRAVITY = 0.05;

    // Blocks per tick an arrow leaves a fully drawn bow and a crossbow at.
    public static final double BOW_SPEED = 3;
    public static final double CROSSBOW_SPEED = 3.15;

    // Blocks per tick a thrown pearl or snowball loses downward.
    public static final double THROWN_GRAVITY = 0.03;

    private ProjectileUtil() {
    }
}
