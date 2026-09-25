package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

// Picks the weapon for a hit. AutoWeapon and KillAura and AttributeSwap share it.
// Every search covers the inventory up to a limit. The hotbar size keeps it to the hotbar.
public final class WeaponUtil {

    // AutoWeapon's own defaults for a caller with no settings of its own.
    private static final double DEFAULT_MARGIN = 2;

    private WeaponUtil() {
    }

    // The slot holding the strongest weapon against this target or minus one.
    public static int bestWeaponSlot(LivingEntity target, int limit) {
        return bestWeaponSlot(target, true, DEFAULT_MARGIN, true, limit);
    }

    // The preferred kind wins unless the other hits harder by more than the margin.
    public static int bestWeaponSlot(LivingEntity target, boolean preferSword, double margin,
                                     boolean skipBreaking, int limit) {
        ToDoubleFunction<ItemStack> damage = stack -> damageAgainst(stack, target);
        int swordSlot = bestSlot(stack -> stack.is(ItemTags.SWORDS), damage, skipBreaking, limit);
        int axeSlot = bestSlot(stack -> stack.is(ItemTags.AXES), damage, skipBreaking, limit);
        if (swordSlot == -1 || axeSlot == -1) {
            return swordSlot == -1 ? axeSlot : swordSlot;
        }
        double swordDamage = damage.applyAsDouble(stackAt(swordSlot));
        double axeDamage = damage.applyAsDouble(stackAt(axeSlot));
        if (preferSword) {
            return axeDamage - swordDamage > margin ? axeSlot : swordSlot;
        }
        return swordDamage - axeDamage > margin ? swordSlot : axeSlot;
    }

    // Only an axe staggers a raised shield however hard the sword hits.
    public static int bestAxeSlot(LivingEntity target, boolean skipBreaking, int limit) {
        return bestSlot(stack -> stack.is(ItemTags.AXES), stack -> damageAgainst(stack, target),
            skipBreaking, limit);
    }

    // The sword or axe with the fastest swing.
    public static int fastestWeaponSlot(boolean skipBreaking, int limit) {
        return bestSlot(stack -> stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES),
            stack -> ItemUtil.attributeValue(stack, Attributes.ATTACK_SPEED, EquipmentSlot.MAINHAND),
            skipBreaking, limit);
    }

    // The slot of the highest scoring stack of the kind or minus one.
    private static int bestSlot(Predicate<ItemStack> kind, ToDoubleFunction<ItemStack> score,
                                boolean skipBreaking, int limit) {
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < limit; i++) {
            ItemStack stack = stackAt(i);
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

    private static ItemStack stackAt(int slot) {
        return OfflineClient.MC.player.getInventory().getItem(slot);
    }

    // Damage one full strength hit would deal after their armour and your own effects.
    private static double damageAgainst(ItemStack stack, LivingEntity target) {
        return DamageUtil.attackDamage(OfflineClient.MC.player, target, stack);
    }
}
