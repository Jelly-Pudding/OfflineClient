package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.gui.ClickGuiScreen;
import com.jellypudding.offlineclient.gui.WindowGuiScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

public final class ClickGuiModule extends Module {

    public enum Style {
        PANELS("Panels"),
        WINDOW("Window");

        private final String label;

        Style(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final EnumSetting<Style> style = new EnumSetting<>("Style",
        "Draggable panels or one window with a sidebar.", Style.PANELS);

    private final ColorSetting accent = new ColorSetting("Accent",
        "Main colour of the GUI and HUD.", 190, false);

    public ClickGuiModule() {
        super("ClickGUI", "Opens the GUI and sets its accent colour.",
            Category.MISC, GLFW.GLFW_KEY_RIGHT_SHIFT);
        addSettings(style, accent);
    }

    @Override
    public boolean isTogglable() {
        return false;
    }

    public ColorSetting getAccent() {
        return accent;
    }

    public boolean isWindow() {
        return style.is(Style.WINDOW);
    }

    @Override
    public void onKeybind() {
        open();
    }

    // A screen opened mid key press receives that same press.
    public void open() {
        mc.schedule(() -> {
            Screen screen = style.is(Style.WINDOW) ? new WindowGuiScreen() : new ClickGuiScreen();
            mc.gui.setScreen(screen);
        });
    }
}
