package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.platform.Window;

// The ClickGUI scale held still whilst the pointer is down. Dragging the scale
// slider would otherwise move the slider out from under the pointer dragging it.
public final class LatchedScale {

    private float value = guiScale();
    private boolean held;

    // Everything in the GUI is drawn through this and every pointer position
    // is divided by it to match. The font is a bitmap and goes soft between
    // whole pixels. The chosen size therefore lands on the nearest step the
    // game's own scale allows. It never outgrows the largest scale the game
    // would pick for the window and the GUI always fits.
    public static float guiScale() {
        float wanted = Modules.get(ClickGuiModule.class).scale();
        Window window = OfflineClient.MC.getWindow();
        int vanilla = window.getGuiScale();
        if (vanilla <= 0) {
            return wanted;
        }
        int largest = Math.max(1, window.calculateScale(0, false));
        return Math.clamp(Math.round(vanilla * wanted), 1, largest) / (float) vanilla;
    }

    // Once a frame. A new scale is only picked up whilst nothing is held.
    public void refresh() {
        if (!held) {
            value = guiScale();
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
