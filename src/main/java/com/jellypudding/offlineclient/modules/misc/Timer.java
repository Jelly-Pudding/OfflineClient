package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Modules;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

// Behaviour lives in DeltaTrackerMixin. Other modules can push a temporary override.
public final class Timer extends Module {

    private final NumberSetting speed = new NumberSetting("Speed",
        "Game speed multiplier.", 2, 0.1, 10, 0.1, "x");

    private final Map<String, Float> overrides = new HashMap<>();

    public Timer() {
        super("Timer", "Speeds up or slows down the whole game client side.", Category.MISC);
        addSettings(speed);
    }

    @Override
    public String getSuffix() {
        float effective = getSpeed();
        if (effective == speed.getFloat()) {
            return speed.getValueString();
        }
        return new BigDecimal(Float.toString(effective)).stripTrailingZeros().toPlainString() + "x";
    }

    // The game speed in force right now. One when nothing is speeding it up.
    public static float current() {
        Timer timer = Modules.get(Timer.class);
        return timer == null ? 1f : timer.getSpeed();
    }

    // Pushes an override without the caller having to look the module up first.
    public static void override(String key, float multiplier) {
        Timer timer = Modules.get(Timer.class);
        if (timer != null) {
            timer.setOverride(key, multiplier);
        }
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
