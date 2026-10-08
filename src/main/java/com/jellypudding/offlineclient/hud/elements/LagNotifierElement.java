package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.TickRate;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

// Shows whilst the server has gone quiet. Handy before you step into a hole.
public final class LagNotifierElement extends HudElement {

    private static final double MILLIS_PER_SECOND = 1000;
    // The warning has turned the long lag colour this long after it shows.
    private static final float LONG_LAG_MILLIS = 10_000;

    // How a stall of a few seconds reads. It stands in whilst the editor is open and the
    // server is answering. Its colour has turned a quarter of the way.
    private static final long SAMPLE_MILLIS = 2500;
    private static final String SAMPLE_TPS = "15.0";
    private static final float SAMPLE_RAMP = 0.25f;

    private final NumberSetting threshold = new NumberSetting("Lag threshold",
        "How far past its usual update the server may go quiet before this shows.", 500, 0, 5000, 100, " ms")
        .min(0);
    private final BoolSetting showTime = new BoolSetting("Show how long",
        "Writes how long the server has been quiet.", true);
    private final BoolSetting showTps = new BoolSetting("Show tick rate",
        "Writes the server tick rate too.", true);
    private final ColorSetting color = new ColorSetting("Lag colour",
        "Colour of the warning when it first shows.", 45, 0.85f, 1f, false);
    private final ColorSetting longColor = new ColorSetting("Long lag colour",
        "Colour the warning has turned ten seconds after it shows.", 0, 0.85f, 1f, false);

    public LagNotifierElement() {
        super("Lag notifier", "A warning whilst the server has stopped answering.", false, 50, 40);
        add(threshold, showTime, showTps, color, longColor);
    }

    @Override
    public boolean visible() {
        return isActive() && (lagging() || editing());
    }

    private boolean lagging() {
        return TickRate.INSTANCE.lagging(threshold.getInt());
    }

    private boolean sample() {
        return editing() && !lagging();
    }

    // The full quiet and not only the part past the usual update.
    private long quiet() {
        return sample() ? SAMPLE_MILLIS : TickRate.INSTANCE.millisSinceLastTick();
    }

    private String line() {
        StringBuilder text = new StringBuilder("Server is lagging");
        if (showTime.isOn()) {
            text.append(String.format(Locale.ROOT, " for %.1fs", quiet() / MILLIS_PER_SECOND));
        }
        if (showTps.isOn()) {
            text.append(" at ").append(sample() ? SAMPLE_TPS : ServerInfo.liveTps()).append(" tps");
        }
        return text.toString();
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        float ramp = sample() ? SAMPLE_RAMP
            : (TickRate.INSTANCE.millisLate() - threshold.getInt()) / LONG_LAG_MILLIS;
        write(context, font, line(), 0, 0, ColorUtil.lerpHue(color.getColor(), longColor.getColor(), ramp));
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
