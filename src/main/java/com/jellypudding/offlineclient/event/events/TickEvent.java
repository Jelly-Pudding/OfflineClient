package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;

/**
 * Fired once per client tick while a world is loaded.
 */
public final class TickEvent extends Event {
    public static final TickEvent INSTANCE = new TickEvent();
}
