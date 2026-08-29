package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.NoSlowdown;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SweetBerryBushBlock.class)
public abstract class SweetBerryBushBlockMixin {

    // The bush slows and pricks an entity through this call.
    @Inject(method = "entityInside(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/InsideBlockEffectApplier;Z)V",
        at = @At("HEAD"), cancellable = true)
    private void onEntityInside(BlockState state, Level level, BlockPos pos, Entity entity,
                                InsideBlockEffectApplier applier, boolean precise, CallbackInfo ci) {
        NoSlowdown noSlowdown = Modules.get(NoSlowdown.class);
        if (entity == OfflineClient.MC.player && noSlowdown != null
            && noSlowdown.skipsBerryBushes()) {
            ci.cancel();
        }
    }
}
