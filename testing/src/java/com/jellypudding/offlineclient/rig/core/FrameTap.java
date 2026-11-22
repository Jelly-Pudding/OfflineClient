package com.jellypudding.offlineclient.rig.core;

import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;

import java.util.ArrayList;
import java.util.List;

// Collects what one frame of the interface draws. A mixin of the rig feeds it from the game's
// own list of things to draw and it stops by itself when the frame ends.
public final class FrameTap {

    private static final List<Object> DRAWN = new ArrayList<>();
    private static volatile boolean armed;
    private static volatile boolean collecting;

    private FrameTap() {
    }

    // The next frame that draws anything is kept.
    public static void arm() {
        DRAWN.clear();
        collecting = false;
        armed = true;
    }

    public static void text(GuiTextRenderState text) {
        if (armed) {
            collecting = true;
            DRAWN.add(text);
        }
    }

    public static void element(GuiElementRenderState element) {
        if (armed && element instanceof ColoredRectangleRenderState) {
            collecting = true;
            DRAWN.add(element);
        }
    }

    public static void frameEnded() {
        if (collecting) {
            armed = false;
        }
    }

    public static List<Object> taken() {
        armed = false;
        return new ArrayList<>(DRAWN);
    }
}
