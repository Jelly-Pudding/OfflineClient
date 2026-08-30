package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Works out what an end crystal blast would do before it happens.
 * The numbers mirror the vanilla explosion code exactly.
 */
public final class ExplosionUtil {

    private static final Minecraft MC = OfflineClient.MC;

    private static final BlockPos[] NOTHING = new BlockPos[0];

    public static final float CRYSTAL_POWER = 6f;

    // A respawn anchor and a bed both explode with power five.
    public static final float RESPAWN_BLOCK_POWER = 5f;

    private ExplosionUtil() {
    }

    // An anchor that sets a spawn point never goes off.
    public static boolean anchorsExplodeHere() {
        return !MC.level.environmentAttributes()
            .getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, MC.player.blockPosition());
    }

    // A bed that sets a spawn point never goes off.
    public static boolean bedsExplodeHere() {
        return MC.level.environmentAttributes()
            .getValue(EnvironmentAttributes.BED_RULE, MC.player.blockPosition()).explodes();
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
        return blastDamage(target, source, power, lead, NOTHING);
    }

    /**
     * A bed or an anchor is removed before it goes off. The blocks named here
     * are treated as gone for the line of sight.
     */
    public static float blastDamage(LivingEntity target, Vec3 source, float power, Vec3 lead,
                                    BlockPos... ignored) {
        if (target == null || !target.isAlive()) {
            return 0;
        }
        // Vanilla measures both the falloff and the damage against double the power.
        float diameter = power * 2;
        double distance = target.position().add(lead).distanceTo(source);
        if (distance > diameter) {
            return 0;
        }
        float seen = seenPercent(source, target, ignored);
        double impact = (1 - distance / diameter) * seen;
        float raw = (float) ((impact * impact + impact) / 2 * 7 * diameter + 1);
        return reduce(raw, target);
    }

    // The suicide check refuses anything that could kill.
    public static boolean selfSafe(Vec3 source, float power, float maxSelfDamage,
                                   boolean antiSuicide, BlockPos... ignored) {
        float self = blastDamage(MC.player, source, power, Vec3.ZERO, ignored);
        if (self > maxSelfDamage) {
            return false;
        }
        return !antiSuicide || self < EntityUtil.totalHealth(MC.player);
    }

    // Only the reach is checked when dangerous is off.
    public static boolean respawnBlockThreat(BlockPos pos, double range, boolean onlyDangerous) {
        if (BlockUtil.distanceTo(pos) > range) {
            return false;
        }
        return !onlyDangerous
            || blastDamage(MC.player, Vec3.atCenterOf(pos), RESPAWN_BLOCK_POWER, Vec3.ZERO, halvesOf(pos)) > 0;
    }

    // Both halves of a bed. Any other block is only itself.
    public static BlockPos[] halvesOf(BlockPos pos) {
        BlockState state = MC.level.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)) {
            return new BlockPos[] {pos};
        }
        Direction facing = state.getValue(BedBlock.FACING);
        BlockPos other = state.getValue(BedBlock.PART) == BedPart.HEAD
            ? pos.relative(facing.getOpposite()) : pos.relative(facing);
        return new BlockPos[] {pos, other};
    }

    /**
     * The share of the target the blast can see. The same grid of points
     * vanilla samples over the hitbox. A ray that only meets an ignored block
     * counts as clear.
     */
    private static float seenPercent(Vec3 source, LivingEntity target, BlockPos[] ignored) {
        AABB box = target.getBoundingBox();
        double stepX = 1 / ((box.maxX - box.minX) * 2 + 1);
        double stepY = 1 / ((box.maxY - box.minY) * 2 + 1);
        double stepZ = 1 / ((box.maxZ - box.minZ) * 2 + 1);
        if (stepX < 0 || stepY < 0 || stepZ < 0) {
            return 0;
        }
        double offsetX = (1 - Math.floor(1 / stepX) * stepX) / 2;
        double offsetZ = (1 - Math.floor(1 / stepZ) * stepZ) / 2;
        int seen = 0;
        int total = 0;
        for (double x = 0; x <= 1; x += stepX) {
            for (double y = 0; y <= 1; y += stepY) {
                for (double z = 0; z <= 1; z += stepZ) {
                    Vec3 point = new Vec3(Mth.lerp(x, box.minX, box.maxX) + offsetX,
                        Mth.lerp(y, box.minY, box.maxY), Mth.lerp(z, box.minZ, box.maxZ) + offsetZ);
                    HitResult hit = MC.level.clip(new ClipContext(point, source,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
                    if (hit.getType() == HitResult.Type.MISS
                        || isIgnored(((BlockHitResult) hit).getBlockPos(), ignored)) {
                        seen++;
                    }
                    total++;
                }
            }
        }
        return total == 0 ? 0 : (float) seen / total;
    }

    private static boolean isIgnored(BlockPos pos, BlockPos[] ignored) {
        for (BlockPos ignore : ignored) {
            if (ignore.equals(pos)) {
                return true;
            }
        }
        return false;
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
        for (EquipmentSlot slot : ItemUtil.ARMOR_SLOTS) {
            ItemStack piece = target.getItemBySlot(slot);
            protection += ItemUtil.enchantLevel(Enchantments.PROTECTION, piece);
            protection += 2 * ItemUtil.enchantLevel(Enchantments.BLAST_PROTECTION, piece);
        }
        damage = CombatRules.getDamageAfterMagicAbsorb(damage, protection);

        return Math.max(damage, 0);
    }
}
