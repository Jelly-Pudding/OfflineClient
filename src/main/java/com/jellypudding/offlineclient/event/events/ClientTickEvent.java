package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;

/**
 * Fired once per client tick even in menus. Use TickEvent for in world logic.
 */
public final class ClientTickEvent extends Event {
    public static final ClientTickEvent INSTANCE = new ClientTickEvent();
}
