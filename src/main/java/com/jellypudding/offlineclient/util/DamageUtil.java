package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.NoFall;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// Guesses at the damage the player is about to take from what is already around them.
public final class DamageUtil {

    private static final Minecraft MC = OfflineClient.MC;

    // A player this close could land a hit before the next tick.
    public static final double MELEE_RANGE = 5;

    // How far a crystal or a bed or an anchor is looked for.
    public static final double BLAST_RANGE = 8;

    // Blocks a player can drop without being hurt.
    private static final double SAFE_FALL = 3;

    private DamageUtil() {
    }

    // The worst single thing that could hit the player this tick.
    public static float possibleIncoming() {
        return possibleIncoming(BLAST_RANGE, true, true, true);
    }

    public static float possibleIncoming(double blastRange, boolean blasts, boolean melee, boolean fall) {
        if (MC.player == null || MC.level == null) {
            return 0;
        }
        float worst = 0;
        if (blasts) {
            worst = Math.max(worst, blastThreat(blastRange));
        }
        if (melee) {
            worst = Math.max(worst, meleeThreat(MELEE_RANGE));
        }
        if (fall) {
            worst = Math.max(worst, fallDamage(MC.player));
        }
        return worst;
    }

    // The worst crystal or bed or anchor in range.
    public static float blastThreat(double range) {
        float[] worst = {0};
        for (Entity entity : MC.level.entitiesForRendering()) {
            if (entity instanceof EndCrystal && entity.distanceTo(MC.player) <= range) {
                worst[0] = Math.max(worst[0], ExplosionUtil.crystalDamage(MC.player, entity.position()));
            }
        }
        // Thousands of positions every tick. Nothing is collected and nothing is sorted.
        BlockUtil.forEachWithin(range, pos -> worst[0] = Math.max(worst[0], chargeThreat(pos)));
        return worst[0];
    }

    // Damage a bed or an anchor standing here would deal. Zero when there is none.
    private static float chargeThreat(BlockPos pos) {
        if (!isCharge(BlockUtil.state(pos))) {
            return 0;
        }
        BlockPos charge = pos.immutable();
        return ExplosionUtil.blastDamage(MC.player, Vec3.atCenterOf(charge),
            ExplosionUtil.RESPAWN_BLOCK_POWER, Vec3.ZERO, ExplosionUtil.halvesOf(charge));
    }

    private static boolean isCharge(BlockState state) {
        if (state.getBlock() instanceof BedBlock) {
            return ExplosionUtil.bedsExplodeHere();
        }
        return state.getBlock() instanceof RespawnAnchorBlock && ExplosionUtil.anchorsExplodeHere();
    }

    // The hardest hit any enemy in range could land with what they hold.
    public static float meleeThreat(double range) {
        float worst = 0;
        for (Entity entity : MC.level.entitiesForRendering()) {
            if (!EntityUtil.isEnemy(entity) || entity.distanceTo(MC.player) > range) {
                continue;
            }
            worst = Math.max(worst, attackDamage((Player) entity, MC.player));
        }
        return worst;
    }

    // Damage one full strength hit with what the attacker holds would deal.
    public static float attackDamage(LivingEntity attacker, LivingEntity target) {
        return attackDamage(attacker, target, attacker.getWeaponItem());
    }

    // The same hit with a weapon the attacker is not holding yet.
    // The attacker's own bonuses such as Strength still count.
    public static float attackDamage(LivingEntity attacker, LivingEntity target, ItemStack weapon) {
        double held = ItemUtil.attributeValue(attacker.getWeaponItem(), Attributes.ATTACK_DAMAGE,
            EquipmentSlot.MAINHAND);
        double swapped = ItemUtil.attributeValue(weapon, Attributes.ATTACK_DAMAGE, EquipmentSlot.MAINHAND);
        float damage = (float) (attacker.getAttributeValue(Attributes.ATTACK_DAMAGE) - held + swapped);
        damage += enchantBonus(weapon, target);

        DamageSource source = attacker instanceof Player player
            ? MC.level.damageSources().playerAttack(player) : MC.level.damageSources().mobAttack(attacker);
        damage += weapon.getItem().getAttackDamageBonus(target, damage, source);
        if (canCrit(attacker)) {
            damage *= 1.5f;
        }
        return reduce(damage, target, source);
    }

    // The flat damage the weapon enchantments add against this target.
    public static float enchantBonus(ItemStack weapon, Entity target) {
        float bonus = 0;
        int sharpness = ItemUtil.enchantLevel(Enchantments.SHARPNESS, weapon);
        if (sharpness > 0) {
            bonus += 0.5f * sharpness + 0.5f;
        }
        if (EntityUtil.typeIs(target, EntityTypeTags.SENSITIVE_TO_SMITE)) {
            bonus += 2.5f * ItemUtil.enchantLevel(Enchantments.SMITE, weapon);
        }
        if (EntityUtil.typeIs(target, EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)) {
            bonus += 2.5f * ItemUtil.enchantLevel(Enchantments.BANE_OF_ARTHROPODS, weapon);
        }
        if (EntityUtil.typeIs(target, EntityTypeTags.SENSITIVE_TO_IMPALING)) {
            bonus += 2.5f * ItemUtil.enchantLevel(Enchantments.IMPALING, weapon);
        }
        return bonus;
    }

    // Vanilla only crits a falling attacker who is off the ground and not sprinting.
    private static boolean canCrit(LivingEntity attacker) {
        return attacker.fallDistance > 0 && !attacker.onGround() && !attacker.onClimbable()
            && !attacker.isInWater() && !attacker.hasEffect(MobEffects.BLINDNESS)
            && !attacker.isPassenger() && !attacker.isSprinting();
    }

    // Damage the fall the entity is in would deal on landing. The drop still to come is
    // measured down to the first block or water under them. Zero whilst NoFall is on.
    public static float fallDamage(LivingEntity entity) {
        if (entity == MC.player && Modules.enabled(NoFall.class)) {
            return 0;
        }
        if (entity instanceof Player player && player.getAbilities().flying) {
            return 0;
        }
        if (entity.isFallFlying() || entity.hasEffect(MobEffects.SLOW_FALLING)
            || entity.hasEffect(MobEffects.LEVITATION)) {
            return 0;
        }
        double drop = entity.fallDistance;
        if (!entity.onGround()) {
            Vec3 feet = entity.position();
            BlockHitResult hit = MC.level.clip(new ClipContext(feet,
                new Vec3(feet.x, MC.level.getMinY(), feet.z),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.WATER, entity));
            if (hit.getType() == HitResult.Type.MISS
                || !MC.level.getFluidState(hit.getBlockPos()).isEmpty()) {
                return 0;
            }
            drop += feet.y - hit.getLocation().y;
        }
        drop -= SAFE_FALL;
        MobEffectInstance jump = entity.getEffect(MobEffects.JUMP_BOOST);
        if (jump != null) {
            drop -= jump.getAmplifier() + 1;
        }
        if (drop <= 0) {
            return 0;
        }
        return reduce((float) Math.floor(drop), entity, MC.level.damageSources().fall());
    }

    // Applies difficulty scaling then armour then resistance then enchantments.
    public static float reduce(float damage, LivingEntity target, DamageSource source) {
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

        if (!source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
            damage = CombatRules.getDamageAfterMagicAbsorb(damage, protectionAgainst(target, source));
        }
        return Math.max(damage, 0);
    }

    // The protection points the worn armour offers against this kind of damage.
    private static int protectionAgainst(LivingEntity target, DamageSource source) {
        int points = 0;
        for (EquipmentSlot slot : ItemUtil.ARMOR_SLOTS) {
            ItemStack piece = target.getItemBySlot(slot);
            points += ItemUtil.enchantLevel(Enchantments.PROTECTION, piece);
            if (source.is(DamageTypeTags.IS_FIRE)) {
                points += 2 * ItemUtil.enchantLevel(Enchantments.FIRE_PROTECTION, piece);
            }
            if (source.is(DamageTypeTags.IS_EXPLOSION)) {
                points += 2 * ItemUtil.enchantLevel(Enchantments.BLAST_PROTECTION, piece);
            }
            if (source.is(DamageTypeTags.IS_PROJECTILE)) {
                points += 2 * ItemUtil.enchantLevel(Enchantments.PROJECTILE_PROTECTION, piece);
            }
            if (source.is(DamageTypeTags.IS_FALL)) {
                points += 3 * ItemUtil.enchantLevel(Enchantments.FEATHER_FALLING, piece);
            }
        }
        return points;
    }
}
