package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.TickRate;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

// Says so whilst the server has gone quiet. Handy before you step into a hole.
public final class LagNotifierElement extends HudElement {

    private static final double MILLIS_PER_SECOND = 1000;

    private final NumberSetting threshold = new NumberSetting("Lag threshold",
        "How long the server may go quiet before this shows.", 500, 100, 5000, 100, " ms")
        .min(50);
    private final BoolSetting showTime = new BoolSetting("Show how long",
        "Write how long it has been quiet.", true);
    private final BoolSetting showTps = new BoolSetting("Show tick rate",
        "Write the server tick rate too.", true);
    private final ColorSetting color = new ColorSetting("Lag colour",
        "Colour of the warning.", 0, 0.85f, 1f, false);

    public LagNotifierElement() {
        super("Lag notifier", "A warning whilst the server has stopped answering.", false, 50, 40);
        add(threshold, showTime, showTps, color);
    }

    @Override
    public boolean visible() {
        return isActive() && TickRate.INSTANCE.lagging(threshold.getInt());
    }

    private String line() {
        StringBuilder text = new StringBuilder("Server is lagging");
        if (showTime.isOn()) {
            text.append(String.format(Locale.ROOT, " for %.1fs",
                TickRate.INSTANCE.millisSinceLastTick() / MILLIS_PER_SECOND));
        }
        if (showTps.isOn()) {
            text.append(String.format(Locale.ROOT, " at %.1f tps", TickRate.INSTANCE.tps()));
        }
        return text.toString();
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        context.text(font, line(), 0, 0, color.getColor(), true);
    }

    @Override
    public int width(Font font) {
        return Math.max(1, font.width(line()));
    }

    @Override
    public int height(Font font) {
        return font.lineHeight;
    }
}
