package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.UncancellableEvent;

// Fired once per client tick even in menus. TickEvent is the in world one.
public final class ClientTickEvent extends UncancellableEvent {
    public static final ClientTickEvent INSTANCE = new ClientTickEvent();
}
