package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;

public final class RightClickEvent extends Event {

    private final boolean blank;
    private final boolean byModule;

    public RightClickEvent(boolean blank, boolean byModule) {
        this.blank = blank;
        this.byModule = byModule;
    }

    // True when a feeder or NoInteract hands the game this click as one into the air. Nothing may
    // be clicked in place of what the crosshair rests on.
    public boolean isBlank() {
        return blank;
    }

    // True whilst a module uses an item of its own accord and the player does not hold the key. A
    // meal a feeder takes is such a use and no click of the player's.
    public boolean byModule() {
        return byModule;
    }
}
