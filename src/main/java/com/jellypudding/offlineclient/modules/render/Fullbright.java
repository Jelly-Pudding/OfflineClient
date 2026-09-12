package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.mixinterface.ISimpleOption;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.LightLayer;

// BrightnessGetterMixin asks for the light floor whilst chunks are lit.
public final class Fullbright extends Module {

    public enum Mode { GAMMA, POTION, LUMINANCE }

    public enum Layer { BLOCK, SKY }

    // Night vision only flickers in its last few seconds.
    private static final int POTION_TICKS = 420;

    private static volatile Fullbright instance;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the dark is lit.", Mode.GAMMA)
        .describe(Mode.GAMMA, "Pushes the brightness slider past its cap.")
        .describe(Mode.POTION, "Gives you night vision on your own screen.")
        .describe(Mode.LUMINANCE, "Raises the light level of every block so the world is relit rather than washed out.");
    private final NumberSetting brightness = new NumberSetting("Brightness",
        "Gamma level above the vanilla cap of 1.", 16, 1, 16, 0.5)
        .under(mode, Mode.GAMMA);
    private final EnumSetting<Layer> layer = new EnumSetting<>("Light type",
        "Which kind of light is raised.", Layer.BLOCK)
        .describe(Layer.BLOCK, "Light from torches and lava and glowstone.")
        .describe(Layer.SKY, "Light from the sky.")
        .under(mode, Mode.LUMINANCE);
    private final NumberSetting minimumLight = new NumberSetting("Minimum light",
        "No block is lit below this level.", 8, 0, 15, 1, "").min(0).max(15)
        .under(mode, Mode.LUMINANCE);
    private final BoolSetting fade = new BoolSetting("Fade",
        "Ease the brightness up instead of snapping to it.", true)
        .under(mode, Mode.GAMMA);
    private final NumberSetting fadeStep = new NumberSetting("Fade speed",
        "How much of the gap is closed each tick.", 1, 0.1, 4, 0.1).min(0.05)
        .under(fade, () -> mode.is(Mode.GAMMA) && fade.isOn());
    private final NumberSetting restoreBrightness = new NumberSetting("Restore brightness",
        "The gamma the game goes back to once this is off.", 1, 0, 1, 0.05).min(0).max(1)
        .under(mode, Mode.GAMMA);

    // Where the fade has reached. Jumps straight to the target whilst fade is off.
    private double shown = 1;
    private Mode running;
    private int lastMinimum;
    private Layer lastLayer;

    public Fullbright() {
        super("Fullbright", "See in the dark without torches.", Category.RENDER);
        addSettings(mode, brightness, layer, minimumLight, fade, fadeStep,
            restoreBrightness);
        searchTags("night vision", "brightness");
        instance = this;
    }

    public static Fullbright get() {
        return instance;
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onEnable() {
        // A crash whilst enabled leaves the boosted gamma in options.txt. Vanilla caps at 1.
        shown = Math.min(mc.options.gamma().get(), 1.0);
        running = mode.getValue();
        lastMinimum = minimumLight.getInt();
        lastLayer = layer.getValue();
        if (running == Mode.LUMINANCE) {
            relight();
        }
    }

    @Override
    protected void onDisable() {
        stop(running);
        running = null;
    }

    // Undoes what one mode did. A mode swap whilst on leaves nothing behind.
    private void stop(Mode mode) {
        if (mode == null) {
            return;
        }
        switch (mode) {
            case GAMMA -> setGamma(restoreBrightness.getValue());
            case POTION -> {
                if (mc.player != null) {
                    mc.player.removeEffect(MobEffects.NIGHT_VISION);
                }
            }
            case LUMINANCE -> relight();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (running != mode.getValue()) {
            stop(running);
            running = mode.getValue();
            if (running == Mode.LUMINANCE) {
                relight();
            }
        }
        switch (running) {
            case GAMMA -> setGamma(stepTowards(brightness.getValue()));
            case POTION -> topUpNightVision();
            case LUMINANCE -> {
                if (lastMinimum != minimumLight.getInt() || lastLayer != layer.getValue()) {
                    lastMinimum = minimumLight.getInt();
                    lastLayer = layer.getValue();
                    relight();
                }
            }
        }
    }

    // The gamma to show this tick. Fade walks it towards the target a step at a time.
    private double stepTowards(double target) {
        if (!fade.isOn()) {
            shown = target;
            return shown;
        }
        double step = fadeStep.getValue();
        shown += Math.clamp(target - shown, -step, step);
        return shown;
    }

    private void topUpNightVision() {
        MobEffectInstance current = mc.player.getEffect(MobEffects.NIGHT_VISION);
        if (current == null || current.getDuration() < POTION_TICKS) {
            mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, POTION_TICKS, 0));
        }
    }

    // Every chunk is meshed again with the new light floor.
    private void relight() {
        if (mc.levelExtractor != null) {
            mc.levelExtractor.allChanged();
        }
    }

    // The floor for one light layer or nought when the layer is left alone.
    public int lightFloor(LightLayer type) {
        if (!isEnabled() || running != Mode.LUMINANCE) {
            return 0;
        }
        boolean wanted = type == LightLayer.SKY ? layer.is(Layer.SKY) : layer.is(Layer.BLOCK);
        return wanted ? minimumLight.getInt() : 0;
    }

    @SuppressWarnings("unchecked")
    private void setGamma(double gamma) {
        ((ISimpleOption<Double>) (Object) mc.options.gamma()).offlineclient$forceSetValue(gamma);
    }
}
