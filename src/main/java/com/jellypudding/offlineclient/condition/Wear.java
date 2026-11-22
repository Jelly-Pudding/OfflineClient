package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

final class Wear extends Condition {

    enum Gear {
        ARMOUR(ItemUtil.ARMOR_SLOTS, false),
        WORN(ItemUtil.ARMOR_SLOTS, true),
        HELD(List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND), true),
        ALL(List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND), true);

        private final List<EquipmentSlot> slots;
        private final boolean elytra;

        Gear(List<EquipmentSlot> slots, boolean elytra) {
            this.slots = slots;
            this.elytra = elytra;
        }
    }

    private static final double PERCENT = 100;

    private final NumberSetting line;
    private final EnumSetting<Gear> gear;
    private String reason = "";

    Wear(Frame frame) {
        super(frame, "Durability");
        line = add(new NumberSetting(frame.name("Durability"),
            "A piece of your gear has this share of its durability left or less.", 10, 1, 50, 1, "%")
            .min(0).max(PERCENT));
        gear = add(new EnumSetting<>(frame.name("Durability of"), "Which gear is watched.", Gear.ARMOUR)
            .describe(Gear.ARMOUR, "The armour you wear. An elytra does not count.")
            .describe(Gear.WORN, "The armour and the elytra you wear.")
            .describe(Gear.HELD, "What you hold in either hand.")
            .describe(Gear.ALL, "Everything you wear and hold."));
    }

    @Override
    public boolean holds() {
        Gear watched = gear.getValue();
        for (EquipmentSlot slot : watched.slots) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (ItemUtil.wornBelow(stack, line.getValue() / PERCENT)
                && (watched.elytra || !stack.has(DataComponents.GLIDER))) {
                reason = "Your " + ChatUtil.words(stack.getItem()) + " was down to "
                    + Math.round(ItemUtil.durabilityPercent(stack)) + "% of its durability.";
                return true;
            }
        }
        return false;
    }

    @Override
    public String reason() {
        return reason;
    }
}
