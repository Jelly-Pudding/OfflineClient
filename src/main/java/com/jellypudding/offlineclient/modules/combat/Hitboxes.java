package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.WeaponKinds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

import java.util.List;

// Melee swings apply one margin to every entity regardless of the kinds list.
// Arrows instead read the pick radius of the entity kinds the list names.
public final class Hitboxes extends Module {

    private final NumberSetting expand = new NumberSetting("Expand",
        "How far the hitbox grows on every side.", 0.25, 0.05, 1, 0.05, " blocks")
        .min(0);
    private final RegistryListSetting<EntityType<?>> entities = new RegistryListSetting<>(
        "Entities", "Kinds of entity to grow. Arrows only take the bigger box on these kinds.",
        BuiltInRegistries.ENTITY_TYPE, List.of(EntityTypes.PLAYER));
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Leaves the hitboxes of friends alone.", true);
    private final BoolSetting onlyWithWeapon = new BoolSetting("Only with weapon",
        "Only grows hitboxes whilst one of the ticked weapons is in your main hand.", false);
    private final WeaponKinds weapons = new WeaponKinds(onlyWithWeapon);

    public Hitboxes() {
        super("Hitboxes", "Makes entities easier to hit by growing their hitboxes.", Category.COMBAT);
        addSettings(expand, entities, ignoreFriends, onlyWithWeapon);
        addSettings(weapons.settings());
        searchTags("aim", "reach", "pick");
    }

    @Override
    public String getSuffix() {
        return expand.getValueString();
    }

    // Extra pick radius for one entity. Zero keeps it vanilla.
    public double expansionFor(Entity entity) {
        if (!isEnabled() || entity == mc.player || !holdingWeapon()) {
            return 0;
        }
        if (ignoreFriends.isOn() && EntityUtil.isFriend(entity)) {
            return 0;
        }
        return entities.contains(entity.getType()) ? expand.getValue() : 0;
    }

    // Extra margin for the attack range check.
    public float margin() {
        if (!isEnabled() || entities.resolved().isEmpty() || !holdingWeapon()) {
            return 0;
        }
        return expand.getFloat();
    }

    private boolean holdingWeapon() {
        return !onlyWithWeapon.isOn() || mc.player == null || weapons.matches(mc.player.getMainHandItem());
    }
}
