package com.jellypudding.offlineclient.hud;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

// The panel an element can draw behind itself. Its settings are named after the element.
public final class Backdrop {

    // How far the panel reaches past the element on every side in GUI pixels. The editor
    // draws its outline round an element at the same distance.
    public static final int PAD = 2;

    // A dark blue that keeps white text readable over any sky.
    private static final float HUE = 240;
    private static final float SATURATION = 0.3f;
    private static final float BRIGHTNESS = 0.1f;

    // White text still stands out over white cloud through a panel this solid.
    private static final double OPACITY = 60;
    private static final float PERCENT = 100f;

    private final BoolSetting on;
    private final ColorSetting color;
    private final NumberSetting opacity;

    Backdrop(String name, boolean onByDefault) {
        on = new BoolSetting(name + " background", "Draws a panel behind it.", onByDefault);
        color = new ColorSetting(name + " background colour", "Colour of that panel.", HUE, SATURATION, BRIGHTNESS,
            false).under(on);
        opacity = new NumberSetting(name + " background opacity",
            "How solid the panel is from clear at nought to solid at a hundred.", OPACITY, 0, 100, 5, "%")
            .max(100).under(on);
    }

    List<Setting<?>> settings() {
        return List.of(on, color, opacity);
    }

    // Round a box of this size with its corner at the origin. Nothing whilst the switch is off.
    void around(GuiGraphicsExtractor context, int width, int height) {
        if (on.isOn()) {
            context.fill(-PAD, -PAD, width + PAD, height + PAD,
                ColorUtil.fade(color.getColor(), opacity.getFloat() / PERCENT));
        }
    }
}
