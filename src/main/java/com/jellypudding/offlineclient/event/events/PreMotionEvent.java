package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.UncancellableEvent;

// Fired right before the client sends its movement packets.
public final class PreMotionEvent extends UncancellableEvent {
    public static final PreMotionEvent INSTANCE = new PreMotionEvent();
}
