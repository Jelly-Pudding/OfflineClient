package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.Locale;

// A strip of the compass that slides past as you turn.
public final class CompassElement extends HudElement {

    private static final String[] POINTS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    // Degrees between the marks on the strip.
    private static final int STEP = 45;

    private static final int TICK_HEIGHT = 3;
    private static final int GAP = 2;

    private final NumberSetting span = new NumberSetting("Compass width",
        "How wide the strip is in pixels.", 160, 60, 400, 10, " px").min(40);
    private final NumberSetting field = new NumberSetting("Compass field",
        "How many degrees fit across the strip.", 180, 60, 360, 10, " degrees").min(30);
    private final BoolSetting showYaw = new BoolSetting("Show yaw",
        "Write the exact heading under the strip.", false);
    private final ColorSetting color = new ColorSetting("Compass colour",
        "Colour of the marks.", 190, false);
    private final ColorSetting northColor = new ColorSetting("North colour",
        "Colour of the north mark.", 0, 0.85f, 1f, false);

    public CompassElement() {
        super("Compass", "A sliding strip of the compass points.", false, 50, 8);
        add(span, field, showYaw, color, northColor);
    }

    // Where a heading lands on the strip or minus one whilst it is off the end.
    private double screenX(float yaw, double heading) {
        double offset = Mth.wrapDegrees(heading - yaw);
        double half = field.getValue() / 2.0;
        if (Math.abs(offset) > half) {
            return -1;
        }
        return (offset + half) / field.getValue() * span.getValue();
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        float yaw = player.getYRot();
        int wide = span.getInt();
        context.fill(0, 0, wide, 1, 0x50FFFFFF);
        for (int i = 0; i < POINTS.length; i++) {
            double x = screenX(yaw, i * STEP);
            if (x < 0) {
                continue;
            }
            String point = POINTS[i];
            int tint = point.equals("N") ? northColor.getColor() : color.getColor();
            int left = (int) Math.round(x);
            context.fill(left, 1, left + 1, 1 + TICK_HEIGHT, tint);
            context.text(font, point, left - font.width(point) / 2, 1 + TICK_HEIGHT + GAP,
                tint, true);
        }
        if (showYaw.isOn()) {
            String heading = String.format(Locale.ROOT, "%.0f", Mth.wrapDegrees(yaw + 180));
            context.text(font, heading, (wide - font.width(heading)) / 2,
                1 + TICK_HEIGHT + GAP + font.lineHeight, color.getColor(), true);
        }
    }

    @Override
    public int width(Font font) {
        return span.getInt();
    }

    @Override
    public int height(Font font) {
        int base = 1 + TICK_HEIGHT + GAP + font.lineHeight;
        return showYaw.isOn() ? base + font.lineHeight : base;
    }
}
