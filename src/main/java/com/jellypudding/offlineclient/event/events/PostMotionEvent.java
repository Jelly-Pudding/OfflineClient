package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;

/**
 * Fired right after the client sends its position/rotation packets.
 */
public final class PostMotionEvent extends Event {
    public static final PostMotionEvent INSTANCE = new PostMotionEvent();
}
