package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class ItemUtil {

    // Head to feet. The order armour is worn in.
    public static final List<EquipmentSlot> ARMOR_SLOTS = List.of(
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    // What most players want thrown away without being asked.
    public static final List<Item> JUNK = List.of(
        Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.GRAVEL,
        Items.NETHERRACK, Items.ROTTEN_FLESH, Items.POISONOUS_POTATO, Items.WHEAT_SEEDS);

    // Durability points left before a stack counts as about to break.
    private static final int BREAK_MARGIN = 5;

    private ItemUtil() {
    }

    // Every enchantment the world knows. Empty until a world is loaded.
    public static Collection<String> enchantmentIds() {
        if (OfflineClient.MC.level == null) {
            return List.of();
        }
        return OfflineClient.MC.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).keySet()
            .stream().map(Object::toString).sorted().collect(Collectors.toList());
    }

    public static boolean nearlyBroken(ItemStack stack) {
        return nearlyBroken(stack, BREAK_MARGIN);
    }

    // True when this many durability points or fewer remain.
    public static boolean nearlyBroken(ItemStack stack, int margin) {
        return stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= margin;
    }

    // True when a potion or a tipped arrow carries the effect.
    public static boolean carriesEffect(ItemStack stack, MobEffect effect) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return false;
        }
        for (MobEffectInstance instance : contents.getAllEffects()) {
            if (instance.getEffect().value() == effect) {
                return true;
            }
        }
        return false;
    }

    // How much of the durability is left out of a hundred.
    public static double durabilityPercent(ItemStack stack) {
        return 100.0 * (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage();
    }

    // True when the remaining durability is at or under this share of the whole.
    public static boolean wornBelow(ItemStack stack, double share) {
        if (!stack.isDamageableItem()) {
            return false;
        }
        int max = stack.getMaxDamage();
        return max - stack.getDamageValue() <= max * share;
    }

    // The level of an enchantment on a stack. Zero when absent.
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

    // Mining speed against a block including the Efficiency enchantment.
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

    // Minus one when nothing in the hotbar beats a bare hand.
    public static int bestToolSlot(BlockState state) {
        return bestToolSlot(state, 1, stack -> true, InventoryUtil.HOTBAR_SIZE);
    }

    // The slot among the first few up to the limit that mines the block fastest. A stack
    // counts only when the filter accepts it and it beats the floor speed.
    public static int bestToolSlot(BlockState state, float floor, Predicate<ItemStack> allowed,
                                   int slots) {
        int bestSlot = -1;
        float bestSpeed = floor;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = OfflineClient.MC.player.getInventory().getItem(i);
            if (!allowed.test(stack)) {
                continue;
            }
            float speed = miningSpeed(stack, state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    // As selectBestTool but a slot the player picked themselves is left alone.
    public static void holdBestTool(BlockState state, InventoryUtil.SlotSwap slots) {
        if (slots.isHolding() && !slots.stillMine()) {
            slots.forget();
            return;
        }
        selectBestTool(state, slots);
    }

    public static void selectBestTool(BlockState state, InventoryUtil.SlotSwap slots) {
        int bestSlot = bestToolSlot(state);
        if (bestSlot == -1) {
            return;
        }
        slots.select(bestSlot);
    }

    // Null for items that cannot be worn.
    public static EquipmentSlot equipSlot(ItemStack stack) {
        Equippable equippable = stack.getItem().components().get(DataComponents.EQUIPPABLE);
        return equippable == null ? null : equippable.slot();
    }

    // Sums a flat attribute the item grants in a slot. Multiplier style
    // modifiers scale a base of zero.
    public static double attributeValue(ItemStack stack, Holder<Attribute> attribute, EquipmentSlot slot) {
        ItemAttributeModifiers modifiers = stack.getItem().components()
            .getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double flat = 0;
        double scale = 1;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute() != attribute || !entry.slot().test(slot)) {
                continue;
            }
            double amount = entry.modifier().amount();
            switch (entry.modifier().operation()) {
                case ADD_VALUE -> flat += amount;
                case ADD_MULTIPLIED_BASE -> {
                }
                case ADD_MULTIPLIED_TOTAL -> scale *= 1 + amount;
            }
        }
        return flat * scale;
    }
}
