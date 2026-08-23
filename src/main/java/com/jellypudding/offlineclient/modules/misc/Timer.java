package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;

// Behaviour lives in DeltaTrackerMixin. Other modules can push a temporary override.
public final class Timer extends Module {

    private final NumberSetting speed = new NumberSetting("Speed",
        "Game speed multiplier.", 2, 0.1, 10, 0.1, "x");

    private final java.util.Map<String, Float> overrides = new java.util.HashMap<>();

    public Timer() {
        super("Timer", "Speeds up or slows down the whole game client side.", Category.MISC);
        addSettings(speed);
    }

    @Override
    public String getSuffix() {
        float effective = getSpeed();
        return effective != 1f ? effective + "x" : speed.getValueString();
    }

    // A value of one or less clears that module's boost. The highest active source wins.
    public void setOverride(String key, float multiplier) {
        if (multiplier <= 1f) {
            overrides.remove(key);
        } else {
            overrides.put(key, multiplier);
        }
    }

    public float getSpeed() {
        float best = isEnabled() ? speed.getFloat() : 1f;
        for (float value : overrides.values()) {
            best = Math.max(best, value);
        }
        return best;
    }
}
