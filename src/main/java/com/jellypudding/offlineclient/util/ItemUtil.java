package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Helpers for reading item stats.
 */
public final class ItemUtil {

    private ItemUtil() {
    }

    /** The level of an enchantment on a stack. Zero when absent. */
    public static int enchantLevel(ResourceKey<Enchantment> enchantment, ItemStack stack) {
        if (OfflineClient.MC.level == null || stack.isEmpty()) {
            return 0;
        }
        return OfflineClient.MC.level.registryAccess()
            .lookupOrThrow(Registries.ENCHANTMENT)
            .get(enchantment)
            .map(entry -> EnchantmentHelper.getItemEnchantmentLevel(entry, stack))
            .orElse(0);
    }

    /** Mining speed against a block including the Efficiency enchantment. */
    public static float miningSpeed(ItemStack stack, BlockState state) {
        float speed = stack.getDestroySpeed(state);
        if (speed > 1) {
            int level = enchantLevel(Enchantments.EFFICIENCY, stack);
            if (level > 0) {
                speed += level * level + 1;
            }
        }
        return speed;
    }

    /** The equipment slot an item goes into. Null for items that cannot be worn. */
    public static EquipmentSlot equipSlot(ItemStack stack) {
        Equippable equippable = stack.getItem().components().get(DataComponents.EQUIPPABLE);
        return equippable == null ? null : equippable.slot();
    }

    /**
     * Sums a flat attribute the item grants in a slot. Armor points and
     * toughness use this. Multiplier style modifiers scale a base of zero
     * here.
     */
    public static double attributeValue(ItemStack stack, Holder<Attribute> attribute, EquipmentSlot slot) {
        ItemAttributeModifiers modifiers = stack.getItem().components()
            .getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double result = 0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute() != attribute || !entry.slot().test(slot)) {
                continue;
            }
            double amount = entry.modifier().amount();
            result += switch (entry.modifier().operation()) {
                case ADD_VALUE -> amount;
                case ADD_MULTIPLIED_BASE -> 0;
                case ADD_MULTIPLIED_TOTAL -> amount * result;
            };
        }
        return result;
    }
}
