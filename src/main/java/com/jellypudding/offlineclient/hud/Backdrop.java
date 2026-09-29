package com.jellypudding.offlineclient.hud;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// A panel drawn behind a HUD element. Its switch and colour are named after the element
// and sit with the element's other settings.
public final class Backdrop {

    // A dark blue that keeps white text readable over any sky.
    private static final float HUE = 240;
    private static final float SATURATION = 0.3f;
    private static final float BRIGHTNESS = 0.1f;

    // How far a panel drawn round an element reaches past it.
    private static final int PAD = 2;

    private final BoolSetting on;
    private final ColorSetting color;

    // The rows read the name followed by background and background colour. The words say
    // what the panel sits behind such as the rows.
    public Backdrop(String name, String behind, boolean onByDefault) {
        on = new BoolSetting(name + " background", "Draws a panel behind " + behind + ".", onByDefault);
        color = new ColorSetting(name + " background colour", "Colour of that panel.", HUE, SATURATION, BRIGHTNESS,
            false).under(on);
    }

    public Setting<?>[] settings() {
        return new Setting<?>[] {on, color};
    }

    // Nothing is drawn whilst the switch is off. True when the panel went down.
    public boolean draw(GuiGraphicsExtractor context, int left, int top, int right, int bottom) {
        if (!on.isOn()) {
            return false;
        }
        context.fill(left, top, right, bottom, color.getColor());
        return true;
    }

    // A panel reaching a little past a box of that size with its corner at the origin.
    public boolean around(GuiGraphicsExtractor context, int width, int height) {
        return draw(context, -PAD, -PAD, width + PAD, height + PAD);
    }
}
