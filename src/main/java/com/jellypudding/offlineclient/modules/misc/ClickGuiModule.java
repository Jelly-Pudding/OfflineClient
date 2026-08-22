package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.gui.ClickGuiScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ColorSetting;
import org.lwjgl.glfw.GLFW;

public final class ClickGuiModule extends Module {

    private final ColorSetting accent = new ColorSetting("Accent",
        "Main color of the GUI and HUD.", 190, false);

    public ClickGuiModule() {
        super("ClickGUI", "The GUI itself. The bind opens it and Accent sets the colors.",
            Category.MISC, GLFW.GLFW_KEY_RIGHT_SHIFT);
        addSettings(accent);
    }

    /** There is nothing to turn on or off here. */
    @Override
    public boolean isTogglable() {
        return false;
    }

    public ColorSetting getAccent() {
        return accent;
    }

    @Override
    public void onKeybind() {
        open();
    }

    /**
     * Opens the GUI once the current input event is done. A screen opened
     * mid key press receives that same press.
     */
    public void open() {
        mc.schedule(() -> mc.gui.setScreen(new ClickGuiScreen()));
    }
}
