package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;

public final class AirStrafingSpeedEvent extends Event {

    private float speed;

    public AirStrafingSpeedEvent(float speed) {
        this.speed = speed;
    }

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }
}
