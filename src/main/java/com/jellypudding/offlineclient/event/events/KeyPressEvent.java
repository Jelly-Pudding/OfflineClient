package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;

// Key and action are raw GLFW values.
public final class KeyPressEvent extends Event {

    private final int key;
    private final int scancode;
    private final int action;
    private final int modifiers;

    public KeyPressEvent(int key, int scancode, int action, int modifiers) {
        this.key = key;
        this.scancode = scancode;
        this.action = action;
        this.modifiers = modifiers;
    }

    public int getKey() {
        return key;
    }

    public int getScancode() {
        return scancode;
    }

    public int getAction() {
        return action;
    }

    public int getModifiers() {
        return modifiers;
    }
}
