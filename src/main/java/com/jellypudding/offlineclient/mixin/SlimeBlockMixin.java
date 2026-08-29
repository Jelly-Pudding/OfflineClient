package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.NoSlowdown;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SlimeBlock.class)
public abstract class SlimeBlockMixin {

    // The slime only slows an entity through this call.
    @Inject(method = "stepOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/Entity;)V",
        at = @At("HEAD"), cancellable = true)
    private void onStepOn(Level level, BlockPos pos, BlockState state, Entity entity,
                          CallbackInfo ci) {
        NoSlowdown noSlowdown = Modules.get(NoSlowdown.class);
        if (entity == OfflineClient.MC.player && noSlowdown != null && noSlowdown.skipsSlime()) {
            ci.cancel();
        }
    }
}
