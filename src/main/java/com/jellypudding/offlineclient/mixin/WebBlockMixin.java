package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.NoSlowdown;
import com.jellypudding.offlineclient.modules.movement.NoWeb;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WebBlock.class)
public abstract class WebBlockMixin {

    // The web only slows an entity through this call.
    @Inject(method = "entityInside(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/InsideBlockEffectApplier;Z)V",
        at = @At("HEAD"), cancellable = true)
    private void onEntityInside(BlockState state, Level level, BlockPos pos, Entity entity,
                                InsideBlockEffectApplier applier, boolean precise, CallbackInfo ci) {
        if (entity != OfflineClient.MC.player) {
            return;
        }
        NoWeb noWeb = Modules.get(NoWeb.class);
        NoSlowdown noSlowdown = Modules.get(NoSlowdown.class);
        if ((noWeb != null && noWeb.skipsWebs()) || (noSlowdown != null && noSlowdown.skipsWebs())) {
            ci.cancel();
        }
    }
}
