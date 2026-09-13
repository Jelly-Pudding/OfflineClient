package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;

// Key and action are raw GLFW values.
public final class KeyPressEvent extends Event {

    private final int key;
    private final int action;

    public KeyPressEvent(int key, int action) {
        this.key = key;
        this.action = action;
    }

    public int getKey() {
        return key;
    }

    public int getAction() {
        return action;
    }
}
