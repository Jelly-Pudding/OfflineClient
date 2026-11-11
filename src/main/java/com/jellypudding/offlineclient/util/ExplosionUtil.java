package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// Works out what a blast would do before it happens.
// The numbers mirror the vanilla explosion code exactly.
public final class ExplosionUtil {

    private static final Minecraft MC = OfflineClient.MC;

    private static final BlockPos[] NOTHING = new BlockPos[0];

    public static final float CRYSTAL_POWER = 6f;

    // A respawn anchor and a bed both explode with power five.
    public static final float RESPAWN_BLOCK_POWER = 5f;

    // Lit TNT blows with power four.
    public static final float TNT_POWER = 4f;

    private ExplosionUtil() {
    }

    // The anchor rule never reaches the client. It sits on the dimension type and an anchor only
    // sets a spawn point in the Nether.
    public static boolean anchorsExplodeHere() {
        return !MC.level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER);
    }

    // The bed rule never reaches the client either. Beds blow up in the Nether and the End.
    public static boolean bedsExplodeHere() {
        Holder<DimensionType> type = MC.level.dimensionTypeRegistration();
        return type.is(BuiltinDimensionTypes.NETHER) || type.is(BuiltinDimensionTypes.END);
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

    // Damage with the target carried forward by the lead. Its distance and the share of it the blast
    // sees are both measured there. The blast stays put and the rays run through the world as it stands.
    public static float blastDamage(LivingEntity target, Vec3 source, float power, Vec3 lead) {
        return blastDamage(target, source, power, lead, NOTHING);
    }

    // A bed or an anchor is removed before it goes off.
    // The blocks named here are treated as gone for the line of sight.
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
        float seen = seenPercent(source, target.getBoundingBox().move(lead), target, ignored);
        double impact = (1 - distance / diameter) * seen;
        float raw = (float) ((impact * impact + impact) / 2 * 7 * diameter + 1);
        return reduce(raw, target);
    }

    // True when the blast sees some of the target from inside its reach. Behind full cover it only
    // grazes for the least damage any blast deals.
    public static boolean exposed(LivingEntity target, Vec3 source, float power) {
        return target.position().distanceTo(source) < power * 2
            && seenPercent(source, target.getBoundingBox(), target, NOTHING) > 0;
    }

    // True when the blast deals no friend more than the limit. One that would kill a friend is
    // refused whatever the limit.
    public static boolean friendsSafe(Vec3 source, float power, float maxFriendDamage, BlockPos... ignored) {
        double reach = power * 2;
        for (Player player : MC.level.players()) {
            if (player == MC.player || player.isSpectator() || player.position().distanceToSqr(source) > reach * reach
                || !EntityUtil.isFriend(player)) {
                continue;
            }
            float damage = blastDamage(player, source, power, Vec3.ZERO, ignored);
            if (damage > maxFriendDamage || damage >= EntityUtil.totalHealth(player)) {
                return false;
            }
        }
        return true;
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

    // The share of the box the blast can see using the same grid of points vanilla samples over a
    // hitbox. A ray that only meets an ignored block counts as clear.
    private static float seenPercent(Vec3 source, AABB box, LivingEntity target, BlockPos[] ignored) {
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
            if (pos.equals(ignore)) {
                return true;
            }
        }
        return false;
    }

    // The explosion source carries the blast protection weighting.
    private static float reduce(float damage, LivingEntity target) {
        return DamageUtil.reduce(damage, target, MC.level.damageSources().explosion(null, null));
    }
}
