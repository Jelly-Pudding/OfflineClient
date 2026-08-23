package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.XRay;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {

    // No ambient occlusion shading whilst XRay is on.
    @Inject(
        method = "getShadeBrightness(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F",
        at = @At("RETURN"),
        cancellable = true)
    private void onGetShadeBrightness(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        XRay xray = XRay.get();
        if (xray != null && xray.isEnabled()) {
            cir.setReturnValue(1f);
        }
    }
}
