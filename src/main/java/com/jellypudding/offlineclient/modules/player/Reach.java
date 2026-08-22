package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;

/**
 * The behavior lives in LocalPlayerMixin which calls adjustRange.
 */
public final class Reach extends Module {

    private final NumberSetting range = new NumberSetting("Range",
        "How far you can reach.", 5, 3, 6, 0.05, " blocks");

    public Reach() {
        super("Reach", "Interact with blocks and entities from further away.", Category.PLAYER);
        addSettings(range);
    }

    @Override
    public String getSuffix() {
        return range.getValueString();
    }

    public double adjustRange(double vanilla) {
        // Never shrink below what the game already allows.
        return isEnabled() ? Math.max(vanilla, range.getValue()) : vanilla;
    }
}
