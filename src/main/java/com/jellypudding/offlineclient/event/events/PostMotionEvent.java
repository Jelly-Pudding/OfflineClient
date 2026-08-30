package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;

// Fired right after the client sends its movement packets.
public final class PostMotionEvent extends Event {
    public static final PostMotionEvent INSTANCE = new PostMotionEvent();

    // One shared instance. A cancel would stick for the rest of the session.
    @Override
    public void cancel() {
        throw new UnsupportedOperationException("PostMotionEvent cannot be cancelled");
    }
}
