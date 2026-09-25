package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

// Picks the hotbar weapon for a hit. AutoWeapon and KillAura and AttributeSwap share it.
public final class WeaponUtil {

    // AutoWeapon's own defaults for a caller with no settings of its own.
    private static final double DEFAULT_MARGIN = 2;

    private WeaponUtil() {
    }

    // The hotbar slot holding the strongest weapon against this target or minus one.
    public static int bestWeaponSlot(LivingEntity target) {
        return bestWeaponSlot(target, true, DEFAULT_MARGIN, true);
    }

    // The preferred kind wins unless the other hits harder by more than the margin.
    public static int bestWeaponSlot(LivingEntity target, boolean preferSword, double margin,
                                     boolean skipBreaking) {
        ToDoubleFunction<ItemStack> damage = stack -> damageAgainst(stack, target);
        int swordSlot = bestSlot(stack -> stack.is(ItemTags.SWORDS), damage, skipBreaking);
        int axeSlot = bestSlot(stack -> stack.is(ItemTags.AXES), damage, skipBreaking);
        if (swordSlot == -1 || axeSlot == -1) {
            return swordSlot == -1 ? axeSlot : swordSlot;
        }
        double swordDamage = damage.applyAsDouble(hotbar(swordSlot));
        double axeDamage = damage.applyAsDouble(hotbar(axeSlot));
        if (preferSword) {
            return axeDamage - swordDamage > margin ? axeSlot : swordSlot;
        }
        return swordDamage - axeDamage > margin ? swordSlot : axeSlot;
    }

    // Only an axe staggers a raised shield however hard the sword hits.
    public static int bestAxeSlot(LivingEntity target, boolean skipBreaking) {
        return bestSlot(stack -> stack.is(ItemTags.AXES), stack -> damageAgainst(stack, target), skipBreaking);
    }

    // The sword or axe with the fastest swing.
    public static int fastestWeaponSlot(boolean skipBreaking) {
        return bestSlot(stack -> stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES),
            stack -> ItemUtil.attributeValue(stack, Attributes.ATTACK_SPEED, EquipmentSlot.MAINHAND),
            skipBreaking);
    }

    // The hotbar slot of the highest scoring stack of the kind or minus one.
    private static int bestSlot(Predicate<ItemStack> kind, ToDoubleFunction<ItemStack> score,
                                boolean skipBreaking) {
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = hotbar(i);
            if (!kind.test(stack) || (skipBreaking && ItemUtil.nearlyBroken(stack))) {
                continue;
            }
            double value = score.applyAsDouble(stack);
            if (value > bestScore) {
                bestScore = value;
                best = i;
            }
        }
        return best;
    }

    private static ItemStack hotbar(int slot) {
        return OfflineClient.MC.player.getInventory().getItem(slot);
    }

    // Damage one full strength hit would deal after their armour and your own effects.
    private static double damageAgainst(ItemStack stack, LivingEntity target) {
        return DamageUtil.attackDamage(OfflineClient.MC.player, target, stack);
    }
}
