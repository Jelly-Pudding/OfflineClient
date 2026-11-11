package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;

// Fired before the use key uses the held item on whatever the crosshair is over.
public final class RightClickEvent extends Event {

    private final boolean blank;

    public RightClickEvent(boolean blank) {
        this.blank = blank;
    }

    // True when a feeder or NoInteract hands the game this click as one into the air. Nothing may
    // be clicked in place of what the crosshair rests on.
    public boolean isBlank() {
        return blank;
    }
}
