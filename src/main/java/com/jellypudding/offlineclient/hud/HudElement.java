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

// Its settings hang off the HUD module and the config and the ClickGUI need to know nothing about
// elements.
public abstract class HudElement {

    protected enum Background { NONE, OFF, ON }

    public static final double MIN_STRETCH = 0.25;
    private static final double MAX_STRETCH = 4;

    // Where the stretch sliders stop. A typed or dragged size goes further.
    private static final double SLIDER_STRETCH = 2;

    protected static final int PROBLEM_INK = 0xFFFF5555;

    private static final float LABEL_HUE = 190;
    private static final float LABEL_SATURATION = 0.15f;
    private static final float LABEL_BRIGHTNESS = 0.85f;

    private final String name;
    private final String description;
    private final List<Setting<?>> settings = new ArrayList<>();
    private final List<Setting<?>> last = new ArrayList<>();

    protected final BoolSetting active;
    private final double mostStretch;
    private final NumberSetting x;
    private final NumberSetting y;
    private final NumberSetting across;
    private final NumberSetting down;
    // Null for an element that never has a background.
    private final Backdrop backdrop;
    // It keeps the element as many GUI pixels from the side or the middle its share is nearest on
    // any other screen.
    private final NumberSetting placedWidth;
    private final NumberSetting placedHeight;

    private int boxLeft;
    private int boxTop;
    private int boxWidth;
    private int boxHeight;

    protected HudElement(String name, String description, boolean on, double startX, double startY) {
        this(name, description, on, startX, startY, Background.OFF);
    }

    protected HudElement(String name, String description, boolean on, double startX, double startY,
                         Background background) {
        this(name, description, on, startX, startY, 1, MAX_STRETCH, background);
    }

    protected HudElement(String name, String description, boolean on, double startX, double startY,
                         double startSize) {
        this(name, description, on, startX, startY, startSize, MAX_STRETCH, Background.OFF);
    }

    protected HudElement(String name, String description, boolean on, double startX, double startY,
                         double startSize, double mostStretch, Background background) {
        this(name, description, new BoolSetting(name, description, on), startX, startY, startSize, mostStretch,
            background);
    }

    // Its switch is the module's own on or off state. Two switches for one thing could disagree.
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

    protected static String ordinal(int number) {
        return number == 1 ? "" : " " + number;
    }

    protected static ColorSetting labelColor(String name, String description) {
        return new ColorSetting(name, description, LABEL_HUE, LABEL_SATURATION, LABEL_BRIGHTNESS, false);
    }

    protected final void add(Setting<?>... more) {
        for (Setting<?> setting : more) {
            hang(setting);
            settings.add(setting);
        }
    }

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

    // The switch comes first and the ClickGUI then shows the group closed.
    public final List<Setting<?>> getSettings() {
        List<Setting<?>> all = new ArrayList<>();
        all.add(active);
        all.addAll(getOptions());
        return all;
    }

    public final List<Setting<?>> sizeSettings() {
        return List.of(across, down);
    }

    public final List<Setting<?>> placeSettings() {
        return List.of(x, y);
    }

    public final double scaleX() {
        return across.getValue();
    }

    public final double scaleY() {
        return down.getValue();
    }

    public final double mostStretch() {
        return mostStretch;
    }

    // A picture in picture draw such as a player model never sees the pose and has to be told.
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

    // Nought for an axis still at its starting share. That axis follows whatever screen it is on.
    public final double placedWidth() {
        return x.isDefault() ? 0 : placedWidth.getValue();
    }

    public final double placedHeight() {
        return y.isDefault() ? 0 : placedHeight.getValue();
    }

    public final void moveTo(double xPercent, double yPercent, int screenWidth, int screenHeight) {
        x.setValue(xPercent);
        y.setValue(yPercent);
        placedWidth.setValue((double) screenWidth);
        placedHeight.setValue((double) screenHeight);
    }

    public final boolean positionIsDefault() {
        return x.isDefault() && y.isDefault();
    }

    // The screen it was last placed on stays as it is. A share at its start never reads it.
    public final void resetPosition() {
        x.reset();
        y.reset();
    }

    // Null for an element placed by its anchor alone.
    public Box home(int screenWidth, int screenHeight) {
        return null;
    }

    public boolean touchesEdges() {
        return false;
    }

    // The rest of the overlay sits under any menu and under the tab list.
    public boolean drawsOnTop() {
        return false;
    }

    public boolean staysWhenHidden() {
        return false;
    }

    // It never moves to make room and nothing moves to make room for it.
    public boolean liesOver() {
        return false;
    }

    // Nought is the left edge and one the right edge and a half the middle.
    protected final double alignment() {
        return HudManager.align(x.getValue() / 100);
    }

    public final boolean isActive() {
        return active.isOn();
    }

    public final boolean followsModule() {
        return active instanceof ModuleSwitch;
    }

    public final boolean switchIsAmong(Collection<? extends Setting<?>> settings) {
        return settings.contains(active);
    }

    public final void setActive(boolean on) {
        active.setValue(on);
    }

    // In the editor an element may say something it would not in play such as a sample of what it
    // shows.
    public boolean visible() {
        return active.isOn();
    }

    // One that holds a texture lets go of it.
    public void discard() {
    }

    public static boolean editing() {
        return OfflineClient.MC.gui.screen() instanceof HudEditorScreen;
    }

    protected static boolean textShadow() {
        HudModule hud = Modules.get(HudModule.class);
        return hud == null || hud.textShadow();
    }

    // All overlay text goes through here.
    protected static void write(GuiGraphicsExtractor context, Font font, String text, int x, int y, int color) {
        context.text(font, text, x, y, color, textShadow());
    }

    private boolean blank() {
        return editing() && !visible();
    }

    final int shownWidth(Font font) {
        return blank() ? font.width(name) : width(font);
    }

    final int shownHeight(Font font) {
        return blank() ? font.lineHeight : height(font);
    }

    // Measured in GUI pixels. It keeps the same margin at any stretch.
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
