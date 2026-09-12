package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;

// The behaviour lives in LocalPlayerMixin which asks for each range.
public final class Reach extends Module {

    private final NumberSetting blockRange = new NumberSetting("Block range",
        "How far you can reach a block. Servers refuse anything past six.", 5, 3, 6, 0.05, " blocks")
        .max(10);
    private final NumberSetting entityRange = new NumberSetting("Entity range",
        "How far you can reach an entity. Servers refuse anything past six.", 5, 3, 6, 0.05, " blocks")
        .max(10);

    public Reach() {
        super("Reach", "Reaches blocks and entities from further away.", Category.PLAYER);
        addSettings(blockRange, entityRange);
    }

    @Override
    public String getSuffix() {
        return blockRange.getValueString() + " " + entityRange.getValueString();
    }

    public double adjustBlockRange(double vanilla) {
        return adjust(vanilla, blockRange);
    }

    public double adjustEntityRange(double vanilla) {
        return adjust(vanilla, entityRange);
    }

    // Never shrinks below what the game already allows.
    private double adjust(double vanilla, NumberSetting range) {
        return isEnabled() ? Math.max(vanilla, range.getValue()) : vanilla;
    }
}
