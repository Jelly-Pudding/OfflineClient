package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.gui.ClickGuiScreen;
import com.jellypudding.offlineclient.gui.TabStrip;
import com.jellypudding.offlineclient.gui.WindowGuiScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;

public final class ClickGuiModule extends Module {

    public enum Style { PANELS, WINDOW }

    // How fast every rainbow turns by default and before this module is built.
    public static final double RAINBOW_SPEED = 1;

    private final EnumSetting<Style> style = new EnumSetting<>("Style",
        "How the GUI is laid out.", Style.PANELS)
        .describe(Style.PANELS, "A draggable panel for each category.")
        .describe(Style.WINDOW, "One window with a category sidebar.");

    private final NumberSetting scale = new NumberSetting("Scale",
        "Size everything in the GUI is drawn at. Smaller fits more on screen. The size lands on whole pixels to stay sharp.",
        50, 40, 150, 5, "%").min(30);

    private final BoolSetting hoverHelp = new BoolSetting("Hover help",
        "Explains a module or setting whilst you hover over it.", true);
    private final BoolSetting resetMarks = new BoolSetting("Reset marks",
        "Marks every setting you changed and adds a reset row under each list of settings.", true);
    private final BoolSetting toggleMessages = new BoolSetting("Toggle messages",
        "Says in chat when a bound key or a typed command or a trigger switches a module on or off.", true);
    private final ColorSetting accent = new ColorSetting("Accent",
        "Main colour of the GUI and HUD.", 190, false);
    private final ColorSetting background = new ColorSetting("Background",
        "Colour the panels and rows are shaded from.", 240, 0.40625f, 0.1254902f, false);
    private final ColorSetting text = new ColorSetting("Text",
        "Colour the writing is shaded from.", 240, 0.0327869f, 0.9568627f, false);
    // Nought would stop every rainbow.
    private final NumberSetting rainbowSpeed = new NumberSetting("Rainbow speed",
        "How fast every colour set to RGB goes round the colour wheel.", RAINBOW_SPEED, 0.1, 5, 0.1, "x")
        .min(0.01);
    private final NumberSetting opacity = new NumberSetting("Opacity",
        "How solid the GUI is. Lower lets the world show through.",
        50, 10, 100, 1, "%").min(0).max(100);
    // Nearly white and a faint grey. The same shades the theme text uses.
    private final ColorSetting titleColor = new ColorSetting("Title colour",
        "Colour of the client name at the top of the window.", 240, 0.03f, 0.96f, false)
        .under(style, Style.WINDOW);
    private final ColorSetting versionColor = new ColorSetting("Version colour",
        "Colour of the version number next to it.", 232, 0.2f, 0.55f, false)
        .under(style, Style.WINDOW);

    private final BoolSetting tabs = new BoolSetting("Tabs",
        "The row of tabs across the top of the GUI.", true);

    public ClickGuiModule() {
        super("ClickGUI", "Opens the GUI and sets its accent colour.",
            Category.MISC, InputConstants.KEY_RSHIFT);
        addSettings(style, scale, hoverHelp, resetMarks, toggleMessages, accent, background, text, rainbowSpeed,
            opacity, tabs);
        for (TabStrip.Tab tab : TabStrip.Tab.values()) {
            addSettings(tab.getSetting().under(tabs));
        }
        addSettings(titleColor, versionColor);
    }

    // A share of the normal size. Every GUI screen draws through it.
    public float scale() {
        return scale.getFloat() / 100f;
    }

    public boolean showsTabs() {
        return tabs.isOn();
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

    public boolean showsResetMarks() {
        return resetMarks.isOn();
    }

    public boolean showsToggleMessages() {
        return toggleMessages.isOn();
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

    // How many times faster than normal every colour set to rainbow goes round the colour wheel.
    public double rainbowSpeed() {
        return rainbowSpeed.getValue();
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
