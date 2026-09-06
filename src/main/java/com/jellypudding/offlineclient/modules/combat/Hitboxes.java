package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

// Melee swings apply one margin to every entity regardless of the kinds list.
// Arrows instead read the pick radius of the entity kinds the list names.
public final class Hitboxes extends Module {

    private enum Weapon {
        SWORD(stack -> stack.is(ItemTags.SWORDS)),
        AXE(stack -> stack.is(ItemTags.AXES)),
        PICKAXE(stack -> stack.is(ItemTags.PICKAXES)),
        SHOVEL(stack -> stack.is(ItemTags.SHOVELS)),
        HOE(stack -> stack.is(ItemTags.HOES)),
        MACE(stack -> stack.getItem() instanceof MaceItem),
        SPEAR(stack -> stack.is(ItemTags.SPEARS)),
        TRIDENT(stack -> stack.getItem() instanceof TridentItem);

        private final Predicate<ItemStack> matches;

        Weapon(Predicate<ItemStack> matches) {
            this.matches = matches;
        }
    }

    private final NumberSetting expand = new NumberSetting("Expand",
        "How far the hitbox grows on every side.", 0.25, 0.05, 1, 0.05, " blocks")
        .min(0).max(2);
    private final RegistryListSetting<EntityType<?>> entities = new RegistryListSetting<>(
        "Entities", "Kinds of entity to grow. Arrows only take the bigger box on these kinds.",
        BuiltInRegistries.ENTITY_TYPE, List.of(EntityTypes.PLAYER));
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Leaves the hitboxes of friends alone.", true);
    private final BoolSetting onlyWithWeapon = new BoolSetting("Only with weapon",
        "Only grows hitboxes whilst one of the ticked weapons is in your main hand.", false);
    private final Map<Weapon, BoolSetting> weapons = new EnumMap<>(Weapon.class);

    public Hitboxes() {
        super("Hitboxes", "Makes entities easier to hit by growing their hitboxes.", Category.COMBAT);
        addSettings(expand, entities, ignoreFriends, onlyWithWeapon);
        for (Weapon weapon : Weapon.values()) {
            String label = EnumSetting.label(weapon);
            String article = weapon == Weapon.AXE ? "an " : "a ";
            BoolSetting setting = new BoolSetting(label,
                "Counts " + article + label.toLowerCase(Locale.ROOT) + " as a weapon.", true)
                .under(onlyWithWeapon);
            weapons.put(weapon, setting);
            addSettings(setting);
        }
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
        if (!onlyWithWeapon.isOn() || mc.player == null) {
            return true;
        }
        ItemStack held = mc.player.getMainHandItem();
        for (Weapon weapon : Weapon.values()) {
            if (weapons.get(weapon).isOn() && weapon.matches.test(held)) {
                return true;
            }
        }
        return false;
    }
}
