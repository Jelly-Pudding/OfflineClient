package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.platform.Window;

// Dragging the scale slider would otherwise move the slider out from under the pointer.
public final class LatchedScale {

    private float value = guiScale();
    private boolean held;

    // The font is a bitmap and goes soft between whole pixels. The chosen size lands on the nearest
    // step the game's own scale allows and never outgrows the largest scale the game would pick for
    // the window.
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

    // A new scale is only picked up whilst nothing is held.
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
