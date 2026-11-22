package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;

// Cancelling keeps the hotbar still.
public final class MouseScrollEvent extends Event {

    private final double amount;

    public MouseScrollEvent(double amount) {
        this.amount = amount;
    }

    // Positive scrolls up.
    public double getAmount() {
        return amount;
    }
}
