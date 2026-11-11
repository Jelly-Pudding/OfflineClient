package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

// How fast a block breaks by the vanilla dig speed maths and when the server takes it as broken.
public final class BreakTime {

    private static final Minecraft MC = OfflineClient.MC;

    // The vanilla break time formula. Off the ground mines five times slower and
    // the wrong tool takes over three times as long.
    public static final float AIR_PENALTY = 5;
    private static final int RIGHT_TOOL_DIVISOR = 30;
    private static final int WRONG_TOOL_DIVISOR = 100;
    // Each level of Mining Fatigue multiplies the speed by this once more.
    private static final double FATIGUE_BASE = 0.3;
    // The server finishes a block once the client reports this much progress.
    public static final float SERVER_ACCEPTS = 0.7f;

    private BreakTime() {
    }

    public static boolean canInstantBreak(BlockPos pos) {
        if (MC.player.getAbilities().instabuild) {
            return true;
        }
        return BlockUtil.state(pos).getDestroyProgress(MC.player, MC.level, pos) >= 1;
    }

    // Grass or a flower or snow or anything else in the way that one hit clears. Flowers
    // cannot be built over like grass but still break in one hit.
    public static boolean clearsInOneHit(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        return !state.isAir() && (state.canBeReplaced() || state.is(BlockTags.FLOWERS))
            && state.getFluidState().isEmpty() && canInstantBreak(pos);
    }

    // True when a start and a stop sent together break the block. The server finishes it on
    // the stop once a single tick of progress reaches its threshold.
    public static boolean breaksInOneTick(BlockPos pos) {
        if (MC.player.getAbilities().instabuild) {
            return true;
        }
        return BlockUtil.state(pos).getDestroyProgress(MC.player, MC.level, pos) >= SERVER_ACCEPTS;
    }

    // Progress one tick of mining with this tool would make by the vanilla dig speed maths.
    // The tool need not be held and a packet miner can time one it swapped away.
    public static float breakDelta(ItemStack tool, BlockPos pos) {
        return breakDelta(tool, BlockUtil.state(pos), pos);
    }

    // The same for a block the client has already cleared whilst the server still holds it.
    public static float breakDelta(ItemStack tool, BlockState state, BlockPos pos) {
        float hardness = state.getDestroySpeed(MC.level, pos);
        if (hardness < 0) {
            return 0;
        }
        if (hardness == 0) {
            return 1;
        }
        Player player = MC.player;
        float speed = ItemUtil.miningSpeed(tool, state);
        if (MobEffectUtil.hasDigSpeed(player)) {
            speed *= 1 + (MobEffectUtil.getDigSpeedAmplification(player) + 1) * 0.2f;
        }
        MobEffectInstance fatigue = player.getEffect(MobEffects.MINING_FATIGUE);
        if (fatigue != null) {
            speed *= (float) Math.pow(FATIGUE_BASE, fatigue.getAmplifier() + 1);
        }
        speed *= (float) player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);
        if (player.isEyeInFluid(FluidTags.WATER)) {
            speed *= (float) player.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
        }
        if (!player.onGround()) {
            speed /= AIR_PENALTY;
        }
        return speed / breakDivisor(hardness, ItemUtil.getsDrops(tool, state));
    }

    // The break speed over this is the share of the block mined each tick.
    public static float breakDivisor(float hardness, boolean rightTool) {
        return hardness * (rightTool ? RIGHT_TOOL_DIVISOR : WRONG_TOOL_DIVISOR);
    }

    public static int breakTicks(BlockPos pos) {
        float perTick = BlockUtil.state(pos).getDestroyProgress(MC.player, MC.level, pos);
        if (perTick <= 0) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(1 / perTick);
    }
}
