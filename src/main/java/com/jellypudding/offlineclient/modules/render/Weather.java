package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributeLayer;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.timeline.AttributeTrackSampler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// LevelMixin hands these levels back for the client's own level in place of the real ones.
public final class Weather extends Module {

    // Level.isBrightOutside holds whilst the sky is darkened by fewer whole levels of light than this.
    private static final int DARK_SKY = 4;

    private final NumberSetting rain = new NumberSetting("Rain",
        "How heavy the rain looks. Zero is a clear sky.", 0, 0, 1, 0.05).max(1);
    private final NumberSetting thunder = new NumberSetting("Thunder",
        "How dark a storm looks. Zero is no storm.", 0, 0, 1, 0.05).max(1);

    private static boolean paused;

    public Weather() {
        super("Weather", "Sets the rain and thunder you see and clears them by default.", Category.RENDER);
        addSettings(rain, thunder);
        searchTags("clear skies", "no rain", "weather changer");
    }

    @Override
    public String getSuffix() {
        return rain.getValue() == 0 && thunder.getValue() == 0 ? "Clear" : null;
    }

    public float rainLevel() {
        return rain.getFloat();
    }

    public float thunderLevel() {
        return thunder.getFloat();
    }

    public static boolean paused() {
        return paused;
    }

    // Runs a rule or takes a reading against the real weather and time and not the ones shown.
    public static <T> T real(Supplier<T> reading) {
        paused = true;
        try {
            return reading.get();
        } finally {
            paused = false;
        }
    }

    // The server lets a player sleep by the sky light of the real weather and time. The level keeps the
    // light of the sky shown for the tick and this works the real one out afresh without touching it.
    public static boolean darkOutside(ClientLevel level) {
        // Only a level with weather lets the weather into its sky light. Without a sampler nothing moves
        // the sky light from its default.
        boolean weatherShown = Modules.enabled(Weather.class) && level.canHaveWeather();
        EnvironmentAttributeSystem.ValueSampler<Float> skyLight =
            level.environmentAttributes().getValueSampler(EnvironmentAttributes.SKY_LIGHT_LEVEL);
        if (!weatherShown && !Modules.enabled(TimeChanger.class) || skyLight == null) {
            return level.isDarkOutside();
        }
        List<Runnable> restores = new ArrayList<>();
        for (EnvironmentAttributeLayer<Float> layer : skyLight.layers) {
            if (layer instanceof AttributeTrackSampler<?, ?> timeline) {
                restores.add(forgetReading(timeline));
            }
        }
        try {
            return !level.dimensionType().hasFixedTime()
                && real(() -> (int) (LightEngine.MAX_LEVEL - skyLight.computeValueNotPositional()) >= DARK_SKY);
        } finally {
            restores.forEach(Runnable::run);
        }
    }

    // A timeline reads the clock once a tick and keeps the reading for the rest of it. This drops the
    // kept reading for one read of the real clock and hands back what puts it back.
    private static <A> Runnable forgetReading(AttributeTrackSampler<?, A> timeline) {
        int tickId = timeline.cachedTickId;
        A reading = timeline.cachedArgument;
        timeline.cachedArgument = null;
        return () -> {
            timeline.cachedTickId = tickId;
            timeline.cachedArgument = reading;
        };
    }
}
