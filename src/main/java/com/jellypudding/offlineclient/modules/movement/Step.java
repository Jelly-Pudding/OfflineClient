package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;

public final class Step extends Module {

    private final NumberSetting height = new NumberSetting("Height",
        "How high you can step up without jumping.", 1, 0.6, 3, 0.1, " blocks");

    public Step() {
        super("Step", "Step up full blocks without jumping.", Category.MOVEMENT);
        addSettings(height);
    }

    @Override
    public String getSuffix() {
        return height.getValueString();
    }

    /** Called from LocalPlayerMixin.maxUpStep(). */
    public float adjustStepHeight(float vanilla) {
        if (!isEnabled()) {
            return vanilla;
        }
        // The sneak edge check uses the step height as its drop probe.
        if (mc.player != null && mc.player.isShiftKeyDown()) {
            return vanilla;
        }
        return Math.max(vanilla, height.getFloat());
    }
}
