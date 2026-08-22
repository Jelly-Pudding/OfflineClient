package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
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

    /** A crystal explodes with power six. Damage scales on double that. */
    private static final float CRYSTAL_DIAMETER = 12f;

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private ExplosionUtil() {
    }

    /** Damage a crystal exploding at the point would deal to the entity. */
    public static float crystalDamage(LivingEntity target, Vec3 source) {
        if (target == null || !target.isAlive()) {
            return 0;
        }
        double distance = Math.sqrt(target.distanceToSqr(source));
        if (distance > CRYSTAL_DIAMETER) {
            return 0;
        }
        float seen = ServerExplosion.getSeenPercent(source, target);
        double impact = (1 - distance / CRYSTAL_DIAMETER) * seen;
        float raw = (float) ((impact * impact + impact) / 2 * 7 * CRYSTAL_DIAMETER + 1);
        return reduce(raw, target);
    }

    /** Health plus absorption. What the entity can lose before dying. */
    public static float totalHealth(LivingEntity entity) {
        return entity.getHealth() + entity.getAbsorptionAmount();
    }

    /** Applies difficulty scaling then armor then resistance then enchantments. */
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
