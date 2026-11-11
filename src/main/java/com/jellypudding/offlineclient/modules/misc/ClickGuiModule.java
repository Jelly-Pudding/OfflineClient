package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.gui.ClickGuiScreen;
import com.jellypudding.offlineclient.gui.TabStrip;
import com.jellypudding.offlineclient.gui.WindowGuiScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.KeyUse;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

import java.util.Arrays;
import java.util.List;

public final class ClickGuiModule extends Module {

    public enum Style { PANELS, WINDOW }

    // How fast every rainbow turns by default and before this module is built.
    public static final double RAINBOW_SPEED = 1;

    private static final List<String> CATEGORIES =
        Arrays.stream(Category.values()).map(Category::getDisplayName).toList();

    private final EnumSetting<Style> style = new EnumSetting<>("Style",
        "How the GUI is laid out.", Style.PANELS)
        .describe(Style.PANELS, "A draggable panel for each category.")
        .describe(Style.WINDOW, "One window with a category sidebar.");

    private final NumberSetting scale = new NumberSetting("Scale",
        "Size everything in the GUI is drawn at. Smaller fits more on screen. The size lands on whole pixels to stay sharp.",
        50, 40, 150, 5, "%").min(30);
    private final BoolSetting smoothFont = new BoolSetting("Smooth font",
        "Draws the text of every client screen in a smooth font. Put a font.ttf in the offlineclient folder"
            + " to use your own.", false);

    private final BoolSetting hoverHelp = new BoolSetting("Hover help",
        "Explains a module or setting whilst you hover over it.", true);
    private final BoolSetting resetMarks = new BoolSetting("Reset marks",
        "Marks every setting you changed and adds a reset row under each list of settings.", true);
    private final BoolSetting toggleMessages = new BoolSetting("Toggle messages",
        "Says in chat when a bound key or a typed command or a trigger switches a module on or off. It also says"
            + " when switching one module on turns off another that clashes with it.", true);
    private final ChoiceListSetting quietModules = new ChoiceListSetting("Quiet modules",
        "Modules that are switched on and off without a word in chat. Click to pick them.",
        () -> OfflineClient.INSTANCE.getModuleManager().togglableNames(this))
        .under(toggleMessages);
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

    // The blue friends are drawn in by default and before this module is built.
    public static final float FRIEND_HUE = 220;
    public static final float FRIEND_SATURATION = 0.75f;

    private final ColorSetting friendColor = new ColorSetting("Friend colour",
        "Colour your friends are drawn in by every module that marks them.",
        FRIEND_HUE, FRIEND_SATURATION, 1f, false);

    // The purple enemies are drawn in by default and before this module is built.
    public static final float ENEMY_HUE = 300;
    public static final float ENEMY_SATURATION = 0.75f;

    private final ColorSetting enemyColor = new ColorSetting("Enemy colour",
        "Colour the players on your enemy list are drawn in by every module that marks them.",
        ENEMY_HUE, ENEMY_SATURATION, 1f, false);

    private final ChoiceListSetting hiddenCategories = new ChoiceListSetting("Hidden categories",
        "Categories left out of the GUI. Click to pick them. The search still finds their modules. "
            + "Picking every one brings them all back.",
        () -> CATEGORIES);
    // Goes up with every change to the hidden modules. A screen that is open knows to list again.
    private int hiddenChanges;

    private final ChoiceListSetting hiddenModules = new ChoiceListSetting("Hidden modules",
        "Single modules left out of the GUI. Click to pick them. The search still finds them.",
        ClickGuiModule::moduleNames)
        .onChange(() -> hiddenChanges++);
    private final BoolSetting tabs = new BoolSetting("Tabs",
        "The row of tabs across the top of the GUI.", true);
    private final KeybindSetting commandKey = new KeybindSetting("Command key",
        "Opens the chat box with the command prefix typed in. Whilst this is still the full stop key it gives way"
            + " to anything else you put on that key.", InputConstants.KEY_PERIOD).heardWhilstOff();

    // The ClickGUI is never switched on. Its keys are listened for all the same.
    private final Object keys = new Object() {
        @Subscribe
        private void onKeyPress(KeyPressEvent event) {
            if (mc.gui.screen() == null && inGame() && event.presses(commandKey) && !givesWay()) {
                openCommandBox();
            }
        }
    };

    public ClickGuiModule() {
        super("ClickGUI", "Opens the GUI and sets its accent colour.",
            Category.MISC, InputConstants.KEY_RSHIFT);
        addSettings(style, scale, hoverHelp, resetMarks, toggleMessages, quietModules, accent, background, text,
            rainbowSpeed, opacity, hiddenCategories, hiddenModules, tabs);
        for (TabStrip.Tab tab : TabStrip.Tab.values()) {
            addSettings(tab.getSetting().under(tabs));
        }
        addSettings(titleColor, versionColor, commandKey);
        addSettings(friendColor);
        addSettings(enemyColor);
        addSettings(smoothFont);
        searchTags("command key", "chat prefix", "hide modules", "quiet", "toggle messages");
        watch(keys);
    }

    private static List<String> moduleNames() {
        return OfflineClient.INSTANCE.getModuleManager().getAll().stream().map(Module::getName).toList();
    }

    // The default key belongs to whatever else was put on it. A key the player chose is theirs.
    private boolean givesWay() {
        return commandKey.isDefault() && !KeyUse.others(commandKey.getValue(), commandKey).isEmpty();
    }

    // A screen opened mid key press receives that same press.
    private void openCommandBox() {
        String prefix = OfflineClient.INSTANCE.getCommandManager().getPrefix();
        mc.schedule(() -> mc.gui.setScreen(new ChatScreen(prefix, false)));
    }

    // A share of the normal size. Every GUI screen draws through it.
    public float scale() {
        return scale.getFloat() / 100f;
    }

    public boolean showsTabs() {
        return tabs.isOn();
    }

    public boolean smoothFont() {
        return smoothFont.isOn();
    }

    // A hidden category has no panel and no row in the sidebar. With every one picked none is
    // hidden because a GUI with nothing in it would offer no way back.
    public boolean showsCategory(Category category) {
        return !hiddenCategories.contains(category.getDisplayName())
            || hiddenCategories.getValue().containsAll(CATEGORIES);
    }

    // A hidden module has no row under its category. The search and the favourites and the
    // list of what is on still show it and nothing is ever out of reach.
    public boolean showsModule(Module module) {
        return !hiddenModules.contains(module.getName());
    }

    // The modules of a category that are not hidden in the order the panels show them.
    public List<Module> shownIn(Category category) {
        List<Module> shown = OfflineClient.INSTANCE.getModuleManager().getByCategory(category);
        shown.removeIf(module -> !showsModule(module));
        return shown;
    }

    public int hiddenChanges() {
        return hiddenChanges;
    }

    public int titleColor() {
        return titleColor.getColor();
    }

    public int versionColor() {
        return versionColor.getColor();
    }

    public int friendColor() {
        return friendColor.getColor();
    }

    public int enemyColor() {
        return enemyColor.getColor();
    }

    public boolean showsHoverHelp() {
        return hoverHelp.isOn();
    }

    public boolean showsResetMarks() {
        return resetMarks.isOn();
    }

    // False for a module that is switched without a word.
    public boolean showsToggleMessages(Module module) {
        return toggleMessages.isOn() && !quietModules.contains(module.getName());
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
