package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.NoFall;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
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
    private static final double MELEE_RANGE = 5;

    // How far a crystal or a bed or an anchor is looked for.
    public static final double BLAST_RANGE = 8;

    // Blocks a player can drop without being hurt.
    public static final double SAFE_FALL = 3;

    // What each level of Strength adds to a hit and Weakness takes from it.
    private static final double STRENGTH_PER_LEVEL = 3;
    private static final double WEAKNESS_PER_LEVEL = 4;

    private DamageUtil() {
    }

    // Health and absorption left once the worst hit on its way lands.
    public static float healthAfterIncoming() {
        return healthAfterIncoming(BLAST_RANGE, true, true, true);
    }

    public static float healthAfterIncoming(double blastRange, boolean blasts, boolean melee, boolean fall) {
        return EntityUtil.totalHealth(MC.player) - possibleIncoming(blastRange, blasts, melee, fall);
    }

    private static float possibleIncoming(double blastRange, boolean blasts, boolean melee, boolean fall) {
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
    private static float blastThreat(double range) {
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
    private static float meleeThreat(double range) {
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

    // The same hit with a weapon the attacker is not holding yet. The weapon's own bonus such
    // as a mace's comes before a critical hit multiplies it and the enchantments after.
    public static float attackDamage(LivingEntity attacker, LivingEntity target, ItemStack weapon) {
        DamageSource source = attacker instanceof Player player
            ? MC.level.damageSources().playerAttack(player) : MC.level.damageSources().mobAttack(attacker);
        float damage = hitDamage(attacker, weapon);
        damage += weapon.getItem().getAttackDamageBonus(target, damage, source);
        if (canCrit(attacker)) {
            damage *= 1.5f;
        }
        damage += enchantBonus(weapon, target);
        return reduce(damage, target, source);
    }

    // A full strength hit with the weapon before its enchantments and the target's armour. The
    // client never learns anyone's attack damage and builds it from the attacker's base and the
    // weapon. Only your own effects ever reach the client.
    public static float hitDamage(LivingEntity attacker, ItemStack weapon) {
        double damage = attacker.getAttributeBaseValue(Attributes.ATTACK_DAMAGE)
            + ItemUtil.attributeValue(weapon, Attributes.ATTACK_DAMAGE, EquipmentSlot.MAINHAND)
            + STRENGTH_PER_LEVEL * effectLevel(attacker, MobEffects.STRENGTH)
            - WEAKNESS_PER_LEVEL * effectLevel(attacker, MobEffects.WEAKNESS);
        return (float) Math.max(0, damage);
    }

    // Nought when the effect is not on.
    private static int effectLevel(LivingEntity entity, Holder<MobEffect> effect) {
        MobEffectInstance instance = entity.getEffect(effect);
        return instance == null ? 0 : instance.getAmplifier() + 1;
    }

    // The flat damage the weapon enchantments add against this target.
    private static float enchantBonus(ItemStack weapon, Entity target) {
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
    public static boolean canCrit(LivingEntity attacker) {
        return critFall(attacker) && !attacker.isSprinting();
    }

    // Everything the server asks of a critical hit apart from the sprint.
    public static boolean critFall(LivingEntity attacker) {
        return attacker.fallDistance > 0 && !attacker.onGround() && !attacker.onClimbable()
            && !attacker.isInWater() && !attacker.hasEffect(MobEffects.BLINDNESS)
            && !attacker.isPassenger();
    }

    // The drop a mover takes unhurt. Three blocks for a boat or for no mover at all. A server that lowers
    // the attribute is followed. A raised one such as Jump Boost may run out before the landing and never
    // stretches the drop past three blocks.
    public static double safeFall(Entity mover) {
        return mover instanceof LivingEntity living
            ? Math.min(SAFE_FALL, living.getAttributeValue(Attributes.SAFE_FALL_DISTANCE)) : SAFE_FALL;
    }

    // Damage the fall the entity is in would deal on landing. The drop still to come is
    // measured down to the first block or water under them and that block charges it. Zero
    // whilst NoFall is on.
    private static float fallDamage(LivingEntity entity) {
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
        BlockPos ground = entity.getOnPos();
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
            ground = hit.getBlockPos();
        }
        float damage = Landing.on(MC.level.getBlockState(ground)).damage(entity, drop);
        return damage <= 0 ? 0 : reduce(damage, entity, MC.level.damageSources().fall());
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

        // Falls and pearls and magic go straight through armour.
        if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            float armor = (float) Math.floor(target.getAttributeValue(Attributes.ARMOR));
            float toughness = (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
            damage = CombatRules.getDamageAfterAbsorb(target, damage, source, armor, toughness);
        }

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
