package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;

public final class HighJump extends Module {

    private final NumberSetting boost = new NumberSetting("Boost",
        "Extra upward motion added to every jump.", 0.5, 0.1, 2, 0.1);

    public HighJump() {
        super("HighJump", "Jump higher than normal.", Category.MOVEMENT);
        addSettings(boost);
    }

    /** Called from LocalPlayerMixin.getJumpPower(). */
    public float getAdditionalJumpMotion() {
        return isEnabled() ? boost.getFloat() : 0f;
    }
}
