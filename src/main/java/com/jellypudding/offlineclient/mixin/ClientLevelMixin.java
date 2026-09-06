package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.VehicleTickEvent;
import com.jellypudding.offlineclient.modules.render.ClearView;
import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    // The vehicle ticks before its riders. This is the last word before it moves.
    @Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void onVehicleTick(Entity entity, CallbackInfo ci) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null && player.isPassenger() && entity == player.getRootVehicle()) {
            OfflineClient.INSTANCE.getEventBus().post(new VehicleTickEvent(entity));
        }
    }

    // Vanilla only names the barrier whilst a creative player holds one.
    @Inject(method = "getMarkerParticleTarget()Lnet/minecraft/world/level/block/Block;",
        at = @At("RETURN"), cancellable = true)
    private void onMarkerParticleTarget(CallbackInfoReturnable<Block> cir) {
        NoRender noRender = Modules.get(NoRender.class);
        if (cir.getReturnValue() == null && noRender != null && noRender.showsBarriers()) {
            cir.setReturnValue(Blocks.BARRIER);
        }
    }

    // Block crumbs are made here and never pass through the particle engine's options path.
    @Inject(method = "addDestroyBlockEffect(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At("HEAD"), cancellable = true)
    private void onDestroyBlockEffect(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (offlineclient$blocksBlockParticles()) {
            ci.cancel();
        }
    }

    @Inject(method = "addBreakingBlockEffect(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)V",
        at = @At("HEAD"), cancellable = true)
    private void onBreakingBlockEffect(BlockPos pos, Direction side, CallbackInfo ci) {
        if (offlineclient$blocksBlockParticles()) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean offlineclient$blocksBlockParticles() {
        ClearView clearView = Modules.active(ClearView.class);
        return clearView != null && clearView.blocksParticle(ParticleTypes.BLOCK);
    }
}
