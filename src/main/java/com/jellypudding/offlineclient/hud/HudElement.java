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

    private final String name;
    private final String description;
    private final List<Setting<?>> settings = new ArrayList<>();

    protected final BoolSetting active;
    protected final NumberSetting scale;
    private final NumberSetting x;
    private final NumberSetting y;

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
                         double startX, double startY, double startScale) {
        this.name = name;
        this.description = description;
        // Fifteen elements with five or more options each would swamp the HUD panel.
        active = new BoolSetting(name, description, on).startFolded();
        x = percent(" x", "How far across the screen it sits.", startX);
        y = percent(" y", "How far down the screen it sits.", startY);
        scale = new NumberSetting(name + " scale", "How big it is drawn.",
            startScale, 0.25, 2, 0.05, "x").min(0.25).max(4).under(active);
        add(x, y, scale);
    }

    private NumberSetting percent(String suffix, String description, double start) {
        return new NumberSetting(name + suffix, description, start, 0, 100, 0.5, "%")
            .min(0).max(100).under(active);
    }

    // Files settings in display order. Anything not already a sub option of
    // something else goes under the element switch.
    protected final void add(Setting<?>... more) {
        for (Setting<?> setting : more) {
            if (setting.getParent() == null) {
                setting.under(active);
            }
            settings.add(setting);
        }
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

    public final float scale() {
        return scale.getFloat();
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

    // Clamped to the setting's own limits.
    public final void setScale(double value) {
        scale.setValue(value);
    }

    public final void resetScale() {
        scale.reset();
    }

    public final double xPercent() {
        return x.getValue();
    }

    public final double yPercent() {
        return y.getValue();
    }

    public final void moveTo(double xPercent, double yPercent) {
        x.setValue(xPercent);
        y.setValue(yPercent);
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
