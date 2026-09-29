package com.jellypudding.offlineclient.hud;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

// One piece of the overlay. Its settings hang off the HUD module and the config
// and the ClickGUI need to know nothing about elements.
public abstract class HudElement {

    // The narrowest any element can be stretched either way and the widest most can.
    public static final double MIN_STRETCH = 0.25;
    private static final double MAX_STRETCH = 4;

    // Where the stretch sliders stop. A typed or dragged size goes further.
    private static final double SLIDER_STRETCH = 2;

    private final String name;
    private final String description;
    private final List<Setting<?>> settings = new ArrayList<>();

    protected final BoolSetting active;
    private final double mostStretch;
    private final NumberSetting x;
    private final NumberSetting y;
    private final NumberSetting across;
    private final NumberSetting down;
    // The size in GUI pixels of the screen a pinned element was last placed on. Null for an
    // element that is not pinned.
    private NumberSetting placedWidth;
    private NumberSetting placedHeight;

    private int boxLeft;
    private int boxTop;
    private int boxWidth;
    private int boxHeight;

    // addSettings is final and files the settings away without handing out the element.
    @SuppressWarnings("this-escape")
    protected HudElement(String name, String description, boolean on,
                         double startX, double startY) {
        this(name, description, on, startX, startY, 1);
    }

    // A few elements are wordy enough to ship smaller than the rest.
    @SuppressWarnings("this-escape")
    protected HudElement(String name, String description, boolean on,
                         double startX, double startY, double startSize) {
        this(name, description, on, startX, startY, startSize, MAX_STRETCH);
    }

    // An element that has to reach across any screen such as a cover sets how far it stretches.
    @SuppressWarnings("this-escape")
    protected HudElement(String name, String description, boolean on,
                         double startX, double startY, double startSize, double mostStretch) {
        this.name = name;
        this.description = description;
        this.mostStretch = mostStretch;
        // Fifteen elements with five or more options each would swamp the HUD panel.
        active = new BoolSetting(name, description, on).startFolded();
        x = percent(" x", "How far across the screen it sits.", startX);
        y = percent(" y", "How far down the screen it sits.", startY);
        across = stretch(" width scale", "How wide it is drawn next to its normal size.", startSize);
        down = stretch(" height scale", "How tall it is drawn next to its normal size.", startSize);
        add(x, y, across, down);
    }

    private NumberSetting percent(String suffix, String description, double start) {
        return new NumberSetting(name + suffix, description, start, 0, 100, 0.5, "%")
            .min(0).max(100);
    }

    private NumberSetting stretch(String suffix, String description, double start) {
        return new NumberSetting(name + suffix, description, start, MIN_STRETCH, SLIDER_STRETCH, 0.05, "x")
            .min(MIN_STRETCH).max(mostStretch);
    }

    // Files settings in display order. Anything not already a sub option of
    // something else goes under the element switch. Like a module's settings
    // they show whether the element is on or off.
    protected final void add(Setting<?>... more) {
        for (Setting<?> setting : more) {
            if (setting.getParent() == null) {
                setting.under(active, () -> true);
            }
            settings.add(setting);
        }
    }

    // Keeps the element as many GUI pixels from the side or the middle its share is nearest
    // when the screen changes size. The size of the screen it was placed on saves unseen.
    protected final void pin() {
        placedWidth = placedSize(" screen width", "width");
        placedHeight = placedSize(" screen height", "height");
        settings.add(placedWidth);
        settings.add(placedHeight);
    }

    private NumberSetting placedSize(String suffix, String side) {
        return new NumberSetting(name + suffix, "The " + side + " of the screen it was last placed on in GUI pixels.",
            0, 0, 1, 1).min(0).internal();
    }

    public final String getName() {
        return name;
    }

    public final String getDescription() {
        return description;
    }

    // Everything but the switch. The list draws that as a toggle instead.
    public final List<Setting<?>> getOptions() {
        return List.copyOf(settings);
    }

    // The switch itself first. The ClickGUI then shows the group closed.
    public final List<Setting<?>> getSettings() {
        List<Setting<?>> all = new ArrayList<>(settings.size() + 1);
        all.add(active);
        all.addAll(settings);
        return all;
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

    public final void resetSize() {
        across.reset();
        down.reset();
    }

    public final double xPercent() {
        return x.getValue();
    }

    public final double yPercent() {
        return y.getValue();
    }

    // The screen a pinned element was last placed on. Nought for any other and before then.
    public final double placedWidth() {
        return placedWidth == null ? 0 : placedWidth.getValue();
    }

    public final double placedHeight() {
        return placedHeight == null ? 0 : placedHeight.getValue();
    }

    // The anchor as a share of a screen of this size.
    public final void moveTo(double xPercent, double yPercent, int screenWidth, int screenHeight) {
        x.setValue(xPercent);
        y.setValue(yPercent);
        if (placedWidth != null) {
            placedWidth.setValue((double) screenWidth);
            placedHeight.setValue((double) screenHeight);
        }
    }

    public final boolean positionIsDefault() {
        return x.isDefault() && y.isDefault();
    }

    public final void resetPosition() {
        x.reset();
        y.reset();
        if (placedWidth != null) {
            placedWidth.reset();
            placedHeight.reset();
        }
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

    // How the element lines up against its anchor. Nought is the left edge and one
    // the right edge and a half the middle.
    protected final double alignment() {
        return HudManager.align(x.getValue() / 100);
    }

    // True whilst the element is switched on even if it has nothing to say.
    public final boolean isActive() {
        return active.isOn();
    }

    public final void setActive(boolean on) {
        active.setValue(on);
    }

    // False for an element with nothing to say right now.
    public boolean visible() {
        return active.isOn();
    }

    // Drawn with its own top left corner at the origin.
    public abstract void render(GuiGraphicsExtractor context, Font font);

    public abstract int width(Font font);

    public abstract int height(Font font);
}
