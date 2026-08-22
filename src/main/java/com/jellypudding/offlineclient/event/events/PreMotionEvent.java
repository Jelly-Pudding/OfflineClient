package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;

/**
 * Fired right before the client sends its position/rotation packets.
 */
public final class PreMotionEvent extends Event {
    public static final PreMotionEvent INSTANCE = new PreMotionEvent();
}
