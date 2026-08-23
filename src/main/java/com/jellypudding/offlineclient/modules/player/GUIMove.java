package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * Lets the mouse turn the camera whilst a chest or inventory is open. Hold
 * mode keeps the pointer usable for clicking and only looks around whilst the
 * chosen key is held. InvWalk is the separate module for walking about.
 */
public final class GUIMove extends Module {

    public enum Hold {
        ALT("Left alt", GLFW.GLFW_KEY_LEFT_ALT),
        CONTROL("Left control", GLFW.GLFW_KEY_LEFT_CONTROL),
        SHIFT("Left shift", GLFW.GLFW_KEY_LEFT_SHIFT),
        GRAVE("Grave", GLFW.GLFW_KEY_GRAVE_ACCENT);

        private final String label;
        private final int key;

        Hold(String label, int key) {
            this.label = label;
            this.key = key;
        }

        public int key() {
            return key;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Mode {
        HOLD("Hold a key"),
        ALWAYS("Always");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Hold keeps the pointer free for clicking. Always turns the whole time.", Mode.HOLD);
    private final EnumSetting<Hold> hold = new EnumSetting<>("Hold key",
        "Keep this key down to look around.", Hold.ALT)
        .visibleWhen(() -> mode.is(Mode.HOLD));

    private boolean turning;
    private double savedX;
    private double savedY;

    public GUIMove() {
        super("GUIMove", "Look around whilst a chest or inventory is open.", Category.PLAYER);
        addSettings(mode, hold);
        searchTags("gui move", "inv rotate", "menu look", "inventory look");
    }

    // The mouse mixin reads this to hide the screen from the movement handler.
    public boolean isTurning() {
        return isEnabled() && turning;
    }

    @Override
    public String getSuffix() {
        if (turning) {
            return "turning";
        }
        // Naming the key here is the only hint most people get.
        return mode.is(Mode.HOLD) ? hold.getValue().toString().toLowerCase() : "always";
    }

    @Override
    protected void onDisable() {
        stopTurning();
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!inGame()) {
            stopTurning();
            return;
        }
        Screen screen = mc.gui.screen();
        if (screen == null || !InvWalk.allowed(screen)) {
            stopTurning();
            return;
        }
        if (mode.is(Mode.ALWAYS)
            || InputConstants.isKeyDown(mc.getWindow(), hold.getValue().key())) {
            startTurning();
        } else {
            stopTurning();
        }
    }

    private void startTurning() {
        if (turning || mc.getWindow() == null || mc.mouseHandler.isMouseGrabbed()) {
            return;
        }
        turning = true;
        // The pointer comes back to this position on release.
        savedX = mc.mouseHandler.xpos();
        savedY = mc.mouseHandler.ypos();
        mc.mouseHandler.setIgnoreFirstMove();
        InputConstants.grabOrReleaseMouse(mc.getWindow(),
            InputConstants.CURSOR_DISABLED, savedX, savedY);
    }

    private void stopTurning() {
        if (!turning) {
            return;
        }
        turning = false;
        // Closing the screen makes the game take the pointer back in the same tick.
        if (mc.getWindow() == null || mc.mouseHandler.isMouseGrabbed()) {
            return;
        }
        mc.mouseHandler.setIgnoreFirstMove();
        InputConstants.grabOrReleaseMouse(mc.getWindow(),
            InputConstants.CURSOR_NORMAL, savedX, savedY);
    }
}
