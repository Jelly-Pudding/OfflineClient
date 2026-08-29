package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;

// Behaviour lives in TridentItemMixin.
public final class TridentBoost extends Module {

    private final NumberSetting boost = new NumberSetting("Boost",
        "How many times harder a riptide throws you.", 2, 1, 10, 0.5, "x").min(0.1);
    private final BoolSetting outOfWater = new BoolSetting("Out of water",
        "Riptide works on dry land and in clear weather.", true);

    public TridentBoost() {
        super("TridentBoost", "Makes a riptide trident throw you further.", Category.MOVEMENT);
        addSettings(boost, outOfWater);
        searchTags("riptide");
    }

    @Override
    public String getSuffix() {
        return boost.getValueString();
    }

    public double multiplier() {
        return isEnabled() ? boost.getValue() : 1;
    }

    public boolean allowsDryLand() {
        return isEnabled() && outOfWater.isOn();
    }
}
