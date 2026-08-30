package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;

// Fired once per client tick whilst a world is loaded.
public final class TickEvent extends Event {
    public static final TickEvent INSTANCE = new TickEvent();

    // One shared instance. A cancel would stick for the rest of the session.
    @Override
    public void cancel() {
        throw new UnsupportedOperationException("TickEvent cannot be cancelled");
    }
}
