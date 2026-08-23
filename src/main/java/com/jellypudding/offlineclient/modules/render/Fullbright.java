package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.mixinterface.ISimpleOption;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;

public final class Fullbright extends Module {

    private final NumberSetting brightness = new NumberSetting("Brightness",
        "Gamma level above the vanilla cap of 1.", 16, 1, 16, 0.5);

    private double previousGamma = 1;

    public Fullbright() {
        super("Fullbright", "See in the dark without torches.", Category.RENDER);
        addSettings(brightness);
    }

    @Override
    protected void onEnable() {
        // A crash whilst enabled leaves the boosted gamma in options.txt. Vanilla caps at 1.
        previousGamma = Math.min(mc.options.gamma().get(), 1.0);
    }

    @Override
    protected void onDisable() {
        setGamma(previousGamma);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        setGamma(brightness.getValue());
    }

    @SuppressWarnings("unchecked")
    private void setGamma(double gamma) {
        ((ISimpleOption<Double>) (Object) mc.options.gamma()).offlineclient$forceSetValue(gamma);
    }
}
