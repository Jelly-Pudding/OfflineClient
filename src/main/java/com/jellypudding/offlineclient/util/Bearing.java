package com.jellypudding.offlineclient.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

// A line along the ground from where you stood towards something out of sight. The
// yaw is in radians and turns the way a player does. Nought faces south. The blur says
// how far the yaw may be off in radians and what the line points at lies beyond the
// given distance along it.
public record Bearing(double x, double z, double yaw, double blur, double beyond) {

    private static final double QUARTER_TURN = Math.PI / 2;

    public Bearing(double x, double z, double yaw) {
        this(x, z, yaw, 0, 0);
    }

    public static Bearing between(Vec3 from, Vec3 to) {
        return new Bearing(from.x, from.z, Math.atan2(from.x - to.x, to.z - from.z));
    }

    public double dirX() {
        return -Math.sin(yaw);
    }

    public double dirZ() {
        return Math.cos(yaw);
    }

    public Vec3 along(double distance, double y) {
        return new Vec3(x + dirX() * distance, y, z + dirZ() * distance);
    }

    // How far along the line a point sits. Below nought it is behind where you stood.
    public double ahead(double px, double pz) {
        return dirX() * (px - x) + dirZ() * (pz - z);
    }

    public double offset(double px, double pz) {
        return Math.cos(yaw) * (px - x) + Math.sin(yaw) * (pz - z);
    }

    public double originDistance(Bearing other) {
        return Math.hypot(x - other.x, z - other.z);
    }

    public float degrees() {
        return Mth.wrapDegrees((float) Math.toDegrees(yaw));
    }

    public String compass() {
        return CompassPoint.nearest(degrees()).words();
    }

    // The two ways at right angles to the line such as east or west. A second line taken
    // after walking either way crosses this one best.
    public String sideways() {
        Bearing side = new Bearing(x, z, yaw + QUARTER_TURN);
        Bearing other = new Bearing(x, z, yaw - QUARTER_TURN);
        return side.compass() + " or " + other.compass();
    }
}
