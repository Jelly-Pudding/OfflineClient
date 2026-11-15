package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.AttackEntityEvent;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.modules.movement.ElytraBoost;
import com.jellypudding.offlineclient.modules.player.FastBreak;
import com.jellypudding.offlineclient.modules.player.NoInteract;
import com.jellypudding.offlineclient.modules.world.NoGhostBlocks;
import com.jellypudding.offlineclient.util.AttackTimer;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private float destroyProgress;

    @Shadow
    private BlockPos destroyBlockPos;

    @Shadow
    public abstract boolean destroyBlock(BlockPos pos);

    @Shadow
    protected abstract void startPrediction(ClientLevel level, PredictiveAction action);

    // The block the last BlockBreakEvent named and the player tick it went out on.
    @Unique
    private BlockPos offlineclient$postedPos;

    @Unique
    private int offlineclient$postedTick;

    @Inject(
        method = "attack(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V",
        at = @At("HEAD"))
    private void onAttack(Player player, Entity target, CallbackInfo ci) {
        if (player != minecraft.player) {
            return;
        }
        OfflineClient.INSTANCE.getEventBus().post(new AttackEntityEvent(target));
    }

    @Inject(
        method = "startDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
        at = @At("HEAD"), cancellable = true)
    private void onStartDestroyBlock(BlockPos pos, Direction direction,
                                     CallbackInfoReturnable<Boolean> cir) {
        offlineclient$postBreak(pos);
        if (Modules.toolHeldBack()) {
            cir.setReturnValue(false);
            return;
        }
        FastBreak fastBreak = Modules.active(FastBreak.class);
        if (fastBreak == null || minecraft.level == null) {
            return;
        }
        BlockState state = minecraft.level.getBlockState(pos);
        if (fastBreak.guardsClick(state, pos)) {
            cir.setReturnValue(true);
        } else if (fastBreak.instamines(state, pos)) {
            offlineclient$breakOutright(pos, direction);
            cir.setReturnValue(true);
        }
    }

    // The server finishes a block on the tick after a start and stop pair
    // when one tick of progress passes half way.
    @Unique
    private void offlineclient$breakOutright(BlockPos pos, Direction direction) {
        destroyBlock(pos);
        startPrediction(minecraft.level, sequence -> new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction, sequence));
        startPrediction(minecraft.level, sequence -> new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction, sequence));
    }

    // The progress one held tick adds. FastBreak rounds it up to a finish early.
    @ModifyExpressionValue(
        method = "continueDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getDestroyProgress(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"))
    private float adjustProgress(float original) {
        FastBreak fastBreak = Modules.active(FastBreak.class);
        if (fastBreak == null || minecraft.level == null) {
            return original;
        }
        BlockState state = minecraft.level.getBlockState(destroyBlockPos);
        return fastBreak.adjustProgress(state, destroyProgress, original);
    }

    // AutoTool stops a break that would snap the only tool to hand.
    @Inject(
        method = "continueDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
        at = @At("HEAD"), cancellable = true)
    private void onContinueDestroyBlock(BlockPos pos, Direction direction,
                                        CallbackInfoReturnable<Boolean> cir) {
        offlineclient$postBreak(pos);
        if (Modules.toolHeldBack()) {
            cir.setReturnValue(false);
        }
    }

    // A click and a sweep onto a new block run both heads in one tick. Listeners hear of a block once a tick.
    @Unique
    private void offlineclient$postBreak(BlockPos pos) {
        int tick = minecraft.player.tickCount;
        if (tick == offlineclient$postedTick && pos.equals(offlineclient$postedPos)) {
            return;
        }
        offlineclient$postedPos = pos.immutable();
        offlineclient$postedTick = tick;
        OfflineClient.INSTANCE.getEventBus().post(new BlockBreakEvent(pos));
    }

    // ElytraBoost swaps a real rocket for a client side one before it is spent.
    @Inject(
        method = "useItem(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
        at = @At("HEAD"), cancellable = true)
    private void onUseItem(Player player, InteractionHand hand,
                           CallbackInfoReturnable<InteractionResult> cir) {
        ElytraBoost boost = Modules.active(ElytraBoost.class);
        if (player == minecraft.player && boost != null
            && boost.interceptsUse(player.getItemInHand(hand))) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    // NoInteract drops a block right click made with a chosen hand or a protected item before
    // anything is sent. It says how the right click goes on.
    @Inject(
        method = "useItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
        at = @At("HEAD"), cancellable = true)
    private void onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                             CallbackInfoReturnable<InteractionResult> cir) {
        NoInteract noInteract = Modules.active(NoInteract.class);
        InteractionResult dropped = noInteract == null ? null : noInteract.dropsBlockClick(hand, hit);
        if (dropped != null) {
            cir.setReturnValue(dropped);
        }
    }

    // The same for an entity right click.
    @Inject(
        method = "interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
        at = @At("HEAD"), cancellable = true)
    private void onInteract(Player player, Entity target, EntityHitResult hit, InteractionHand hand,
                            CallbackInfoReturnable<InteractionResult> cir) {
        NoInteract noInteract = Modules.active(NoInteract.class);
        InteractionResult dropped = noInteract == null ? null : noInteract.dropsEntityClick(hand, target, hit);
        if (dropped != null) {
            cir.setReturnValue(dropped);
        }
    }

    // The server swings the arm after a use it takes and begins its attack cooldown again with
    // the swing. The hit timer is told. The game's own count never is.
    @Inject(method = {
        "useItem(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)"
            + "Lnet/minecraft/world/InteractionResult;",
        "useItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;"
            + "Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
        "interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/world/InteractionHand;)"
            + "Lnet/minecraft/world/InteractionResult;"},
        at = @At("RETURN"))
    private void afterUse(CallbackInfoReturnable<InteractionResult> cir) {
        if (cir.getReturnValue() instanceof InteractionResult.Success taken && taken.shouldSwing()) {
            AttackTimer.restarted();
        }
    }

    // The throw key never gives away an item NoInteract protects.
    @Inject(method = "dropItem(Lnet/minecraft/client/player/LocalPlayer;Z)V",
        at = @At("HEAD"), cancellable = true)
    private void onDropItem(LocalPlayer player, boolean whole, CallbackInfo ci) {
        if (Modules.keepsFromThrow(player.getMainHandItem())) {
            ci.cancel();
        }
    }

    // Vanilla clears the block on screen the instant it asks the server to
    // break it. The break effects only play when that write lands.
    @WrapOperation(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean holdBreak(Level level, BlockPos pos, BlockState state, int flags,
                              Operation<Boolean> original) {
        NoGhostBlocks noGhostBlocks = Modules.get(NoGhostBlocks.class);
        if (noGhostBlocks != null && noGhostBlocks.holdsBreaks()) {
            return false;
        }
        return original.call(level, pos, state, flags);
    }
}
