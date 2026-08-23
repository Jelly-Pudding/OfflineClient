package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.AttackEntityEvent;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.modules.world.NoGhostBlocks;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

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
        at = @At("HEAD"))
    private void onStartDestroyBlock(BlockPos pos, Direction direction,
                                     CallbackInfoReturnable<Boolean> cir) {
        OfflineClient.INSTANCE.getEventBus().post(new BlockBreakEvent(pos));
    }

    @Inject(
        method = "continueDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
        at = @At("HEAD"))
    private void onContinueDestroyBlock(BlockPos pos, Direction direction,
                                        CallbackInfoReturnable<Boolean> cir) {
        OfflineClient.INSTANCE.getEventBus().post(new BlockBreakEvent(pos));
    }

    /**
     * Vanilla clears the block on screen the instant it asks the server to break
     * it. The break effects only play when that write lands.
     */
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
