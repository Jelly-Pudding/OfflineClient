package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.AttackEntityEvent;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.modules.movement.ElytraBoost;
import com.jellypudding.offlineclient.modules.player.AutoDrop;
import com.jellypudding.offlineclient.modules.player.FastBreak;
import com.jellypudding.offlineclient.modules.player.NoInteract;
import com.jellypudding.offlineclient.modules.world.NoGhostBlocks;
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
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.DecoratedPotBlock;
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
        OfflineClient.INSTANCE.getEventBus().post(new BlockBreakEvent(pos));
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
        if (fastBreak == null || destroyBlockPos == null || minecraft.level == null) {
            return original;
        }
        BlockState state = minecraft.level.getBlockState(destroyBlockPos);
        return fastBreak.adjustProgress(state, destroyProgress, original);
    }

    @Inject(
        method = "continueDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
        at = @At("HEAD"))
    private void onContinueDestroyBlock(BlockPos pos, Direction direction,
                                        CallbackInfoReturnable<Boolean> cir) {
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

    // NoInteract drops every block right click made with a chosen hand.
    @Inject(
        method = "useItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
        at = @At("HEAD"), cancellable = true)
    private void onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                             CallbackInfoReturnable<InteractionResult> cir) {
        NoInteract noInteract = Modules.active(NoInteract.class);
        if (noInteract != null && noInteract.blocksUseWith(hand)) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        AutoDrop autoDrop = Modules.get(AutoDrop.class);
        if (autoDrop == null || !autoDrop.guardsFrames() || minecraft.level == null) {
            return;
        }
        if (minecraft.level.getBlockState(hit.getBlockPos()).getBlock() instanceof DecoratedPotBlock
            && autoDrop.guards(player.getItemInHand(hand))) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    // NoInteract drops every entity right click made with a chosen hand.
    @Inject(
        method = "interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
        at = @At("HEAD"), cancellable = true)
    private void onInteract(Player player, Entity target, EntityHitResult hit, InteractionHand hand,
                            CallbackInfoReturnable<InteractionResult> cir) {
        NoInteract noInteract = Modules.active(NoInteract.class);
        if (noInteract != null && noInteract.blocksEntityUseWith(hand)) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        AutoDrop autoDrop = Modules.get(AutoDrop.class);
        if (autoDrop != null && autoDrop.guardsFrames() && target instanceof ItemFrame
            && autoDrop.guards(player.getItemInHand(hand))) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    // A guarded item is never thrown from a slot and never posted into a pot.
    @Inject(
        method = "handleContainerInput(IIILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V",
        at = @At("HEAD"), cancellable = true)
    private void onContainerInput(int containerId, int slot, int button, ContainerInput kind,
                                  Player player, CallbackInfo ci) {
        if (kind != ContainerInput.THROW || slot < 0) {
            return;
        }
        AutoDrop autoDrop = Modules.get(AutoDrop.class);
        if (autoDrop == null || slot >= player.containerMenu.slots.size()) {
            return;
        }
        if (autoDrop.guards(player.containerMenu.getSlot(slot).getItem())) {
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
