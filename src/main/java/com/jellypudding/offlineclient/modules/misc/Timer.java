package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;

/**
 * The behavior lives in DeltaTrackerMixin which calls getSpeed. Other
 * modules like Speed can push a temporary override without touching the
 * setting or the enabled state.
 */
public final class Timer extends Module {

    public static final float OFF = 0f;

    private final NumberSetting speed = new NumberSetting("Speed",
        "Game speed multiplier.", 2, 0.1, 10, 0.1, "x");

    private float override = OFF;

    public Timer() {
        super("Timer", "Speeds up or slows down the whole game client side.", Category.MISC);
        addSettings(speed);
    }

    @Override
    public String getSuffix() {
        return override != OFF ? override + "x" : speed.getValueString();
    }

    /** A temporary boost from another module. OFF clears it. */
    public void setOverride(float override) {
        this.override = override;
    }

    public float getSpeed() {
        if (override != OFF) {
            return override;
        }
        return isEnabled() ? speed.getFloat() : 1f;
    }
}
