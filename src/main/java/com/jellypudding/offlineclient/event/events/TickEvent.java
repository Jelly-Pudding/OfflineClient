package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.UncancellableEvent;

// Fired once per client tick whilst a world is loaded.
public final class TickEvent extends UncancellableEvent {
    public static final TickEvent INSTANCE = new TickEvent();
}
