package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;

/**
 * Fired when the server pushes new velocity onto the player. Modify the
 * components to change how much knockback is applied.
 */
public final class KnockbackEvent extends Event {

    private double x;
    private double y;
    private double z;

    public KnockbackEvent(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public void setX(double x) {
        this.x = x;
    }

    public void setY(double y) {
        this.y = y;
    }

    public void setZ(double z) {
        this.z = z;
    }
}
