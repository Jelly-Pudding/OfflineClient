package com.jellypudding.offlineclient.hud;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.gui.HudEditorScreen;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.ModuleSwitch;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

// One piece of the overlay. Its settings hang off the HUD module and the config
// and the ClickGUI need to know nothing about elements.
public abstract class HudElement {

    // Whether an element has a background and whether that starts out switched on.
    protected enum Background { NONE, OFF, ON }

    // The narrowest any element can be stretched either way and the widest most can.
    public static final double MIN_STRETCH = 0.25;
    private static final double MAX_STRETCH = 4;

    // Where the stretch sliders stop. A typed or dragged size goes further.
    private static final double SLIDER_STRETCH = 2;

    // The red a piece writes in when it says why it cannot do what it was asked.
    protected static final int PROBLEM_INK = 0xFFFF5555;

    // The soft blue grey most rows of overlay text start out in.
    private static final float LABEL_HUE = 190;
    private static final float LABEL_SATURATION = 0.15f;
    private static final float LABEL_BRIGHTNESS = 0.85f;

    private final String name;
    private final String description;
    private final List<Setting<?>> settings = new ArrayList<>();
    // Rows that come after the background.
    private final List<Setting<?>> last = new ArrayList<>();

    protected final BoolSetting active;
    private final double mostStretch;
    private final NumberSetting x;
    private final NumberSetting y;
    private final NumberSetting across;
    private final NumberSetting down;
    // Null for an element that never has a background.
    private final Backdrop backdrop;
    // The size in GUI pixels of the screen the element was last placed on. It keeps the element
    // as many GUI pixels from the side or the middle its share is nearest on any other screen.
    private final NumberSetting placedWidth;
    private final NumberSetting placedHeight;

    private int boxLeft;
    private int boxTop;
    private int boxWidth;
    private int boxHeight;

    protected HudElement(String name, String description, boolean on, double startX, double startY) {
        this(name, description, on, startX, startY, Background.OFF);
    }

    // An element whose background starts on or that has none at all.
    protected HudElement(String name, String description, boolean on, double startX, double startY,
                         Background background) {
        this(name, description, on, startX, startY, 1, MAX_STRETCH, background);
    }

    // A few elements are wordy enough to ship smaller than the rest.
    protected HudElement(String name, String description, boolean on, double startX, double startY,
                         double startSize) {
        this(name, description, on, startX, startY, startSize, MAX_STRETCH, Background.OFF);
    }

    // An element that has to reach across any screen such as a cover sets how far it stretches.
    protected HudElement(String name, String description, boolean on, double startX, double startY,
                         double startSize, double mostStretch, Background background) {
        this(name, description, new BoolSetting(name, description, on), startX, startY, startSize, mostStretch,
            background);
    }

    // An element that draws what a module works out. Its switch is the module's own on or off
    // state. Two switches for one thing could disagree.
    protected HudElement(String name, String description, Class<? extends Module> owner, double startX,
                         double startY, Background background) {
        this(name, description, new ModuleSwitch(name, description, owner), startX, startY, 1, MAX_STRETCH,
            background);
    }

    private HudElement(String name, String description, BoolSetting active, double startX, double startY,
                       double startSize, double mostStretch, Background background) {
        this.name = name;
        this.description = description;
        this.mostStretch = mostStretch;
        this.active = active;
        // Fifteen elements with five or more options each would swamp the HUD panel.
        active.startFolded();
        x = percent(" x", "How far across the screen it sits.", startX);
        y = percent(" y", "How far down the screen it sits.", startY);
        across = stretch(" width scale", "How wide it is drawn next to its normal size.", startSize);
        down = stretch(" height scale", "How tall it is drawn next to its normal size.", startSize);
        add(x, y, across, down);
        backdrop = background == Background.NONE ? null : new Backdrop(name, background == Background.ON);
        if (backdrop != null) {
            for (Setting<?> setting : backdrop.settings()) {
                hang(setting);
            }
        }
        placedWidth = placedSize(" screen width", "width");
        placedHeight = placedSize(" screen height", "height");
    }

    private NumberSetting percent(String suffix, String description, double start) {
        return new NumberSetting(name + suffix, description, start, 0, 100, 0.5, "%")
            .max(100);
    }

    private NumberSetting stretch(String suffix, String description, double start) {
        return new NumberSetting(name + suffix, description, start, MIN_STRETCH, SLIDER_STRETCH, 0.05, "x")
            .max(mostStretch);
    }

    private NumberSetting placedSize(String suffix, String side) {
        return new NumberSetting(name + suffix, "The " + side + " of the screen it was last placed on in GUI pixels.",
            0, 0, 1, 1).internal();
    }

    // What follows the name of a piece of a kind there can be several of. The first goes by
    // the plain name.
    protected static String ordinal(int number) {
        return number == 1 ? "" : " " + number;
    }

    // A colour setting that starts out in the soft label colour.
    protected static ColorSetting labelColor(String name, String description) {
        return new ColorSetting(name, description, LABEL_HUE, LABEL_SATURATION, LABEL_BRIGHTNESS, false);
    }

    // Files settings in display order.
    protected final void add(Setting<?>... more) {
        for (Setting<?> setting : more) {
            hang(setting);
            settings.add(setting);
        }
    }

    // Files rows that come last in the block such as the one that adds another piece of the kind.
    public final void addLast(Setting<?>... rows) {
        for (Setting<?> row : rows) {
            hang(row);
            last.add(row);
        }
    }

    // Anything not already a sub option of something else goes under the element switch. Like
    // a module's settings they show whether the element is on or off.
    private void hang(Setting<?> setting) {
        if (setting.getParent() == null) {
            setting.under(active, () -> true);
        }
    }

    public final String getName() {
        return name;
    }

    public final String getDescription() {
        return description;
    }

    // Everything but the switch. The element's own options come before its background and the
    // screen it was last placed on saves unseen at the end.
    public final List<Setting<?>> getOptions() {
        List<Setting<?>> options = new ArrayList<>(settings);
        if (backdrop != null) {
            options.addAll(backdrop.settings());
        }
        options.addAll(last);
        options.add(placedWidth);
        options.add(placedHeight);
        return Collections.unmodifiableList(options);
    }

    // The switch itself first. The ClickGUI then shows the group closed.
    public final List<Setting<?>> getSettings() {
        List<Setting<?>> all = new ArrayList<>();
        all.add(active);
        all.addAll(getOptions());
        return all;
    }

    // The width and height stretch. Putting the size back resets these.
    public final List<Setting<?>> sizeSettings() {
        return List.of(across, down);
    }

    // The share across and down. Putting the element back where it started resets these.
    public final List<Setting<?>> placeSettings() {
        return List.of(x, y);
    }

    public final double scaleX() {
        return across.getValue();
    }

    public final double scaleY() {
        return down.getValue();
    }

    // The widest this element may be stretched either way.
    public final double mostStretch() {
        return mostStretch;
    }

    // Where this element landed on screen. A picture in picture draw such as
    // a player model never sees the pose and has to be told.
    final void place(int left, int top, int width, int height) {
        boxLeft = left;
        boxTop = top;
        boxWidth = width;
        boxHeight = height;
    }

    protected final int boxLeft() {
        return boxLeft;
    }

    protected final int boxTop() {
        return boxTop;
    }

    protected final int boxWidth() {
        return boxWidth;
    }

    protected final int boxHeight() {
        return boxHeight;
    }

    // Clamped to the settings' own limits.
    public final void setSize(double wide, double tall) {
        across.setValue(wide);
        down.setValue(tall);
    }

    public final boolean sizeIsDefault() {
        return across.isDefault() && down.isDefault();
    }

    public final double xPercent() {
        return x.getValue();
    }

    public final double yPercent() {
        return y.getValue();
    }

    // The screen it was last placed on. Nought for an axis still at its starting share and
    // before it has been placed. That axis follows whatever screen it is on.
    public final double placedWidth() {
        return x.isDefault() ? 0 : placedWidth.getValue();
    }

    public final double placedHeight() {
        return y.isDefault() ? 0 : placedHeight.getValue();
    }

    // The anchor as a share of a screen of this size.
    public final void moveTo(double xPercent, double yPercent, int screenWidth, int screenHeight) {
        x.setValue(xPercent);
        y.setValue(yPercent);
        placedWidth.setValue((double) screenWidth);
        placedHeight.setValue((double) screenHeight);
    }

    public final boolean positionIsDefault() {
        return x.isDefault() && y.isDefault();
    }

    // Back to the share it starts at. The screen it was last placed on stays as it is. A share
    // at its start never reads it.
    public final void resetPosition() {
        x.reset();
        y.reset();
    }

    // Where the element sits until the player moves it. Null for an element placed
    // by its anchor alone.
    public Box home(int screenWidth, int screenHeight) {
        return null;
    }

    // True for an element that may sit flush with the screen edge. Everything else keeps
    // a small margin.
    public boolean touchesEdges() {
        return false;
    }

    // True for an element drawn once the game has drawn everything else. The rest of the
    // overlay sits under the tab list and any open screen.
    public boolean drawsOnTop() {
        return false;
    }

    // True for an element that keeps drawing whilst the rest of the overlay steps aside for a
    // menu or the debug screen.
    public boolean staysWhenHidden() {
        return false;
    }

    // True for an element laid over others on purpose such as a cover. It never moves to make
    // room and nothing moves to make room for it.
    public boolean liesOver() {
        return false;
    }

    // How the element lines up against its anchor. Nought is the left edge and one
    // the right edge and a half the middle.
    protected final double alignment() {
        return HudManager.align(x.getValue() / 100);
    }

    // True whilst the element is switched on even if it has nothing to say.
    public final boolean isActive() {
        return active.isOn();
    }

    // True for an element whose switch is the on or off state of the module it draws for.
    public final boolean followsModule() {
        return active instanceof ModuleSwitch;
    }

    // True when the switch of this element is one of the settings.
    public final boolean switchIsAmong(Collection<? extends Setting<?>> settings) {
        return settings.contains(active);
    }

    public final void setActive(boolean on) {
        active.setValue(on);
    }

    // False for an element with nothing to say right now. In the editor an element may say
    // something it would not in play such as a sample of what it shows.
    public boolean visible() {
        return active.isOn();
    }

    // Called as the piece is taken off the overlay. One that holds a texture lets go of it.
    public void discard() {
    }

    // True whilst the HUD editor is open.
    public static boolean editing() {
        return OfflineClient.MC.gui.screen() instanceof HudEditorScreen;
    }

    // Whether overlay text has a shadow. The HUD module says.
    protected static boolean textShadow() {
        HudModule hud = Modules.get(HudModule.class);
        return hud == null || hud.textShadow();
    }

    // All overlay text goes through here.
    protected static void write(GuiGraphicsExtractor context, Font font, String text, int x, int y, int color) {
        context.text(font, text, x, y, color, textShadow());
    }

    // In the editor an element with nothing to show stands in with its name.
    private boolean blank() {
        return editing() && !visible();
    }

    final int shownWidth(Font font) {
        return blank() ? font.width(name) : width(font);
    }

    final int shownHeight(Font font) {
        return blank() ? font.lineHeight : height(font);
    }

    // Measured in GUI pixels round the box the element was placed in. It keeps the same margin
    // at any stretch.
    final void drawBackground(GuiGraphicsExtractor context) {
        if (backdrop != null) {
            backdrop.around(context, boxWidth, boxHeight);
        }
    }

    final void drawContent(GuiGraphicsExtractor context, Font font) {
        if (blank()) {
            write(context, font, name, 0, 0, RenderUtil.MUTED_TEXT);
        } else {
            render(context, font);
        }
    }

    // Drawn with its own top left corner at the origin.
    public abstract void render(GuiGraphicsExtractor context, Font font);

    public abstract int width(Font font);

    public abstract int height(Font font);
}
