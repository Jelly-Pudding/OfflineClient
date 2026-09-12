package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.gui.ClickGuiScreen;
import com.jellypudding.offlineclient.gui.WindowGuiScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

public final class ClickGuiModule extends Module {

    public enum Style { PANELS, WINDOW }

    private final EnumSetting<Style> style = new EnumSetting<>("Style",
        "How the GUI is laid out.", Style.PANELS)
        .describe(Style.PANELS, "A draggable panel for each category.")
        .describe(Style.WINDOW, "One window with a category sidebar.");

    private final BoolSetting hoverHelp = new BoolSetting("Hover help",
        "Explains a module or setting whilst you hover over it.", true);
    private final ColorSetting accent = new ColorSetting("Accent",
        "Main colour of the GUI and HUD.", 190, false);
    private final ColorSetting background = new ColorSetting("Background",
        "Colour the panels and rows are shaded from.", 240, 0.40625f, 0.1254902f, false);
    private final ColorSetting text = new ColorSetting("Text",
        "Colour the writing is shaded from.", 240, 0.0327869f, 0.9568627f, false);
    private final NumberSetting opacity = new NumberSetting("Opacity",
        "How solid the panels are.", 100, 15, 100, 1, "%").min(5).max(100);
    // Nearly white and a faint grey. The same shades the theme text uses.
    private final ColorSetting titleColor = new ColorSetting("Title colour",
        "Colour of the client name at the top of the window.", 240, 0.03f, 0.96f, false)
        .under(style, Style.WINDOW);
    private final ColorSetting versionColor = new ColorSetting("Version colour",
        "Colour of the version number next to it.", 232, 0.2f, 0.55f, false)
        .under(style, Style.WINDOW);

    public ClickGuiModule() {
        super("ClickGUI", "Opens the GUI and sets its accent colour.",
            Category.MISC, GLFW.GLFW_KEY_RIGHT_SHIFT);
        addSettings(style, hoverHelp, accent, background, text, opacity,
            titleColor, versionColor);
    }

    public int titleColor() {
        return titleColor.getColor();
    }

    public int versionColor() {
        return versionColor.getColor();
    }

    public boolean showsHoverHelp() {
        return hoverHelp.isOn();
    }

    @Override
    public boolean isTogglable() {
        return false;
    }

    public ColorSetting getAccent() {
        return accent;
    }

    public int background() {
        return background.getColor();
    }

    public int textColor() {
        return text.getColor();
    }

    // A share from nought to one.
    public float opacity() {
        return opacity.getFloat() / 100f;
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
            Screen screen = isWindow() ? new WindowGuiScreen() : new ClickGuiScreen();
            mc.gui.setScreen(screen);
        });
    }
}
