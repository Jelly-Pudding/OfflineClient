package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import org.lwjgl.glfw.GLFW;

// Turns the camera whilst a chest or inventory is open. Hold mode leaves the
// pointer free for clicking. InvWalk covers walking about.
public final class GUIMove extends Module {

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
    private final KeybindSetting hold = new KeybindSetting("Hold key",
        "Keep this key down to look around.", GLFW.GLFW_KEY_LEFT_ALT)
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
        if (!mode.is(Mode.HOLD)) {
            return "always";
        }
        // Naming the key here is the only hint most people get.
        return hold.isBound() ? hold.getKeyName().toLowerCase() : "no key";
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
        if (screen == null || !allowed(screen)) {
            stopTurning();
            return;
        }
        if (mode.is(Mode.ALWAYS) || (hold.isBound()
            && InputConstants.isKeyDown(mc.getWindow(), hold.getValue()))) {
            startTurning();
        } else {
            stopTurning();
        }
    }

    // InvWalk shuts out every screen that carries a text box. The creative screen
    // always carries its search box. That box only shows in the search tab.
    private static boolean allowed(Screen screen) {
        if (InvWalk.allowed(screen)) {
            return true;
        }
        if (!(screen instanceof CreativeModeInventoryScreen)) {
            return false;
        }
        for (GuiEventListener child : screen.children()) {
            if (child instanceof EditBox box && box.isVisible()) {
                return false;
            }
        }
        return true;
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
