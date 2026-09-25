package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

// A switch per kind of held item that counts as a weapon. Each sits under the
// setting that asks for a weapon at all.
public final class WeaponKinds {

    private enum Kind {
        SWORD(stack -> stack.is(ItemTags.SWORDS)),
        AXE(stack -> stack.is(ItemTags.AXES)),
        PICKAXE(stack -> stack.is(ItemTags.PICKAXES)),
        SHOVEL(stack -> stack.is(ItemTags.SHOVELS)),
        HOE(stack -> stack.is(ItemTags.HOES)),
        MACE(stack -> stack.getItem() instanceof MaceItem),
        SPEAR(stack -> stack.is(ItemTags.SPEARS)),
        TRIDENT(stack -> stack.getItem() instanceof TridentItem);

        private final Predicate<ItemStack> matches;

        Kind(Predicate<ItemStack> matches) {
            this.matches = matches;
        }
    }

    private final Map<Kind, BoolSetting> ticked = new EnumMap<>(Kind.class);

    public WeaponKinds(BoolSetting parent) {
        for (Kind kind : Kind.values()) {
            String label = EnumSetting.label(kind);
            String article = kind == Kind.AXE ? "An " : "A ";
            ticked.put(kind, new BoolSetting(label, article + label.toLowerCase(Locale.ROOT) + " counts.", true)
                .under(parent));
        }
    }

    public Setting<?>[] settings() {
        return ticked.values().toArray(new Setting<?>[0]);
    }

    public boolean matches(ItemStack held) {
        for (Map.Entry<Kind, BoolSetting> entry : ticked.entrySet()) {
            if (entry.getValue().isOn() && entry.getKey().matches.test(held)) {
                return true;
            }
        }
        return false;
    }
}
