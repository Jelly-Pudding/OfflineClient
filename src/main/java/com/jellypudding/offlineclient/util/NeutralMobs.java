package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.setting.EnumSetting;
import net.minecraft.world.entity.Mob;

// When a mob that only fights back counts. Endermen and piglins and wolves are the usual ones.
public enum NeutralMobs {
    ALWAYS, WHEN_ANGRY, NEVER;

    // A mob that is not neutral always passes.
    public boolean admit(Mob mob) {
        if (!EntityUtil.isNeutral(mob)) {
            return true;
        }
        return switch (this) {
            case ALWAYS -> true;
            case WHEN_ANGRY -> !EntityUtil.isCalm(mob);
            case NEVER -> false;
        };
    }

    public static EnumSetting<NeutralMobs> setting(NeutralMobs defaultValue) {
        return new EnumSetting<>("Neutral mobs",
            "When a mob that only fights back counts.", defaultValue)
            .describe(ALWAYS, "Like any other mob.")
            .describe(WHEN_ANGRY, "Only once it has turned on someone.")
            .describe(NEVER, "Never even whilst angry.");
    }
}
