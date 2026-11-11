package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.function.Supplier;

// When a mob that only fights back counts. Endermen and piglins and wolves are the usual ones.
// One rule covers them all and a list lifts single kinds out of it.
public final class NeutralMobs {

    public enum When { ALWAYS, WHEN_ANGRY, NEVER }

    private final EnumSetting<When> when;
    private final RegistryListSetting<EntityType<?>> always;

    public NeutralMobs(When defaultValue) {
        when = new EnumSetting<>("Neutral mobs",
            "When a mob that only fights back counts.", defaultValue)
            .describe(When.ALWAYS, "Whether calm or angry.")
            .describe(When.WHEN_ANGRY, "Only once it has turned on someone.")
            .describe(When.NEVER, "Never even whilst angry.");
        always = new RegistryListSetting<>("Always count",
            "Kinds of neutral mob that count even whilst calm. The rule above is for the rest.",
            BuiltInRegistries.ENTITY_TYPE, List.of())
            .only(type -> EntityKind.of(type) == EntityKind.NEUTRAL)
            .under(when, When.WHEN_ANGRY, When.NEVER);
    }

    public Setting<?>[] settings() {
        return new Setting<?>[] {when, always};
    }

    // Makes the rule a sub option of another setting. The list stays under the rule.
    public void under(Setting<?> parent, Supplier<Boolean> shown) {
        when.under(parent, shown);
    }

    // A mob that is not neutral always passes.
    public boolean admit(Mob mob) {
        if (!EntityUtil.isNeutral(mob) || always.contains(mob.getType())) {
            return true;
        }
        return switch (when.getValue()) {
            case ALWAYS -> true;
            case WHEN_ANGRY -> !EntityUtil.isCalm(mob);
            case NEVER -> false;
        };
    }
}
