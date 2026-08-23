package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;

/**
 * Works out what an end crystal blast would do before it happens.
 * The numbers mirror the vanilla explosion code exactly.
 */
public final class ExplosionUtil {

    private static final Minecraft MC = OfflineClient.MC;

    public static final float CRYSTAL_POWER = 6f;

    // A respawn anchor and a bed both explode with power five.
    public static final float RESPAWN_BLOCK_POWER = 5f;

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private ExplosionUtil() {
    }

    public static float crystalDamage(LivingEntity target, Vec3 source) {
        return crystalDamage(target, source, Vec3.ZERO);
    }

    public static float crystalDamage(LivingEntity target, Vec3 source, Vec3 lead) {
        return blastDamage(target, source, CRYSTAL_POWER, lead);
    }

    public static float blastDamage(LivingEntity target, Vec3 source, float power) {
        return blastDamage(target, source, power, Vec3.ZERO);
    }

    /**
     * Damage with the target carried forward by the lead. The blast stays where it
     * really is and the exposure raycast runs through the world that exists.
     */
    public static float blastDamage(LivingEntity target, Vec3 source, float power, Vec3 lead) {
        if (target == null || !target.isAlive()) {
            return 0;
        }
        // Vanilla measures both the falloff and the damage against double the power.
        float diameter = power * 2;
        double distance = target.position().add(lead).distanceTo(source);
        if (distance > diameter) {
            return 0;
        }
        float seen = ServerExplosion.getSeenPercent(source, target);
        double impact = (1 - distance / diameter) * seen;
        float raw = (float) ((impact * impact + impact) / 2 * 7 * diameter + 1);
        return reduce(raw, target);
    }

    public static float totalHealth(LivingEntity entity) {
        return entity.getHealth() + entity.getAbsorptionAmount();
    }

    // The suicide check refuses anything that could kill.
    public static boolean selfSafe(Vec3 source, float power, float maxSelfDamage,
                                   boolean antiSuicide) {
        float self = blastDamage(MC.player, source, power);
        if (self > maxSelfDamage) {
            return false;
        }
        return !antiSuicide || self < totalHealth(MC.player);
    }

    // Only the reach is checked when dangerous is off.
    public static boolean respawnBlockThreat(BlockPos pos, double range, boolean onlyDangerous) {
        if (BlockUtil.distanceTo(pos) > range) {
            return false;
        }
        return !onlyDangerous
            || blastDamage(MC.player, Vec3.atCenterOf(pos), RESPAWN_BLOCK_POWER) > 0;
    }

    // Applies difficulty scaling then armour then resistance then enchantments.
    private static float reduce(float damage, LivingEntity target) {
        DamageSource source = MC.level.damageSources().explosion(null, null);

        if (source.scalesWithDifficulty()) {
            switch (MC.level.getLevelData().getDifficulty()) {
                case EASY -> damage = Math.min(damage / 2 + 1, damage);
                case HARD -> damage *= 1.5f;
                default -> { }
            }
        }

        float armor = (float) Math.floor(target.getAttributeValue(Attributes.ARMOR));
        float toughness = (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        damage = CombatRules.getDamageAfterAbsorb(target, damage, source, armor, toughness);

        MobEffectInstance resistance = target.getEffect(MobEffects.RESISTANCE);
        if (resistance != null) {
            damage *= 1 - (resistance.getAmplifier() + 1) * 0.2f;
        }

        int protection = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack piece = target.getItemBySlot(slot);
            protection += ItemUtil.enchantLevel(Enchantments.PROTECTION, piece);
            protection += 2 * ItemUtil.enchantLevel(Enchantments.BLAST_PROTECTION, piece);
        }
        damage = CombatRules.getDamageAfterMagicAbsorb(damage, protection);

        return Math.max(damage, 0);
    }
}
