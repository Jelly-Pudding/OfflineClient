package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.UncancellableEvent;

public final class AirStrafingSpeedEvent extends UncancellableEvent {

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
