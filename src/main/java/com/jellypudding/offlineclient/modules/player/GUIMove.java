package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.gui.GuiScreenBase;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;

import java.util.Locale;
import org.lwjgl.glfw.GLFW;

// Turns the camera whilst a chest or inventory is open. Hold mode leaves the
// pointer free for clicking. InvWalk covers walking about.
public final class GUIMove extends Module {

    public enum Mode { HOLD_KEY, ALWAYS, ARROWS_ONLY }

    // The most degrees one frame may turn however long it took.
    private static final float MAX_TURN = 100;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "When the mouse turns the view.", Mode.HOLD_KEY)
        .describe(Mode.HOLD_KEY, "Turns only whilst the key is held. The pointer stays free for clicking.")
        .describe(Mode.ALWAYS, "Turns the whole time the screen is open.")
        .describe(Mode.ARROWS_ONLY, "The mouse never turns the view. Only the arrow keys do.");
    private final KeybindSetting hold = new KeybindSetting("Hold key",
        "Keep this key down to look around.", GLFW.GLFW_KEY_LEFT_ALT)
        .under(mode, Mode.HOLD_KEY);
    private final BoolSetting arrowKeys = new BoolSetting("Arrow keys",
        "The arrow keys turn the view whilst a screen is open.", true);
    private final NumberSetting turnSpeed = new NumberSetting("Turn speed",
        "Degrees a tick the arrow keys turn you.", 4, 0.5, 20, 0.5, " degrees")
        .min(0).under(arrowKeys);

    private boolean turning;
    private double savedX;
    private double savedY;

    public GUIMove() {
        super("GUIMove", "Lets you look around whilst a chest or inventory is open.", Category.PLAYER);
        addSettings(mode, hold, arrowKeys, turnSpeed);
        searchTags("gui move", "inv rotate", "menu look", "inventory look");
    }

    // The mouse mixin reads this to hide the screen from the movement handler.
    public boolean isTurning() {
        return isEnabled() && turning;
    }

    // Read by ScreenMixin. The arrow keys must not move the focus about instead.
    public boolean takesArrows() {
        return isEnabled() && arrowKeys.isOn() && mc.gui.screen() != null
            && allowed(mc.gui.screen());
    }

    @Override
    public String getSuffix() {
        if (turning) {
            return "turning";
        }
        if (!mode.is(Mode.HOLD_KEY)) {
            return mode.getValueString().toLowerCase(Locale.ROOT);
        }
        // Naming the key here is the only hint most people get.
        return hold.isBound() ? hold.getKeyName().toLowerCase(Locale.ROOT) : "no key";
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
        if (screen == null || !allowed(screen) || mode.is(Mode.ARROWS_ONLY)) {
            stopTurning();
            return;
        }
        if (mode.is(Mode.ALWAYS) || hold.isHeld()) {
            startTurning();
        } else {
            stopTurning();
        }
    }

    // Runs every frame. The turn is smooth however slow the ticks are.
    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!arrowKeys.isOn() || !inGame()) {
            return;
        }
        Screen screen = mc.gui.screen();
        if (screen == null || !allowed(screen)) {
            return;
        }
        float step = Math.min((float) (turnSpeed.getValue() * mc.getDeltaTracker().getRealtimeDeltaTicks()), MAX_TURN);
        float yaw = 0;
        float pitch = 0;
        if (arrowDown(GLFW.GLFW_KEY_LEFT)) {
            yaw -= step;
        }
        if (arrowDown(GLFW.GLFW_KEY_RIGHT)) {
            yaw += step;
        }
        if (arrowDown(GLFW.GLFW_KEY_UP)) {
            pitch -= step;
        }
        if (arrowDown(GLFW.GLFW_KEY_DOWN)) {
            pitch += step;
        }
        if (yaw == 0 && pitch == 0) {
            return;
        }
        Freecam freecam = Modules.get(Freecam.class);
        if (freecam != null && freecam.movesCamera()) {
            // Freecam scales its turn the way the mouse handler does.
            freecam.turn(yaw / InputUtil.MOUSE_TURN, pitch / InputUtil.MOUSE_TURN);
            return;
        }
        mc.player.setYRot(mc.player.getYRot() + yaw);
        mc.player.setXRot(Mth.clamp(mc.player.getXRot() + pitch, -90, 90));
    }

    private boolean arrowDown(int key) {
        return InputConstants.isKeyDown(mc.getWindow(), key);
    }

    // The client's own screens need the pointer for clicking.
    private static boolean allowed(Screen screen) {
        return !(screen instanceof GuiScreenBase) && InvWalk.walkable(screen);
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
