package com.jellypudding.offlineclient.gui;

// The ClickGUI scale held still whilst the pointer is down. Dragging the scale
// slider would otherwise move the slider out from under the pointer dragging it.
public final class LatchedScale {

    private float value = GuiScreenBase.guiScale();
    private boolean held;

    // Once a frame. A new scale is only picked up whilst nothing is held.
    public void refresh() {
        if (!held) {
            value = GuiScreenBase.guiScale();
        }
    }

    public float get() {
        return value;
    }

    public boolean isHeld() {
        return held;
    }

    public void hold(boolean held) {
        this.held = held;
    }

    public double toView(double screen) {
        return screen / value;
    }
}
