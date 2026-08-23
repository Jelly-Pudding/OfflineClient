package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.XRay;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// XRay hooks for water and lava.
@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {

    @Inject(
        method = "tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void onTesselate(BlockAndTintGetter level, BlockPos pos, FluidRenderer.Output output,
                             BlockState blockState, FluidState fluidState, CallbackInfo ci) {
        XRay xray = XRay.get();
        if (xray == null || !xray.isEnabled()) {
            XRay.setMeshAlpha(-1);
            return;
        }
        int alpha = xray.alphaFor(level, fluidState.createLegacyBlock(), null);
        XRay.setMeshAlpha(alpha);
        if (alpha == 0) {
            ci.cancel();
        }
    }

    // A wanted fluid keeps its faces against hidden blocks.
    @WrapOperation(
        method = "tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/FluidRenderer;isFaceOccludedByNeighbor(Lnet/minecraft/core/Direction;FLnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean onFaceOccluded(Direction side, float height, BlockState neighborState,
                                   Operation<Boolean> original, BlockAndTintGetter level, BlockPos pos,
                                   FluidRenderer.Output output, BlockState blockState, FluidState fluidState) {
        boolean occluded = original.call(side, height, neighborState);
        if (!occluded) {
            return false;
        }
        XRay xray = XRay.get();
        if (xray == null || !xray.isEnabled()) {
            return true;
        }
        if (!xray.isVisible(fluidState.createLegacyBlock().getBlock())) {
            return true;
        }
        return xray.isVisible(neighborState.getBlock());
    }

    @ModifyArg(
        method = "vertex(Lcom/mojang/blaze3d/vertex/VertexConsumer;FFFIFFI)V",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;addVertex(FFFIFFIIFFF)V"),
        index = 3)
    private int onVertexColor(int color) {
        if (!XRay.meshingTranslucent()) {
            return color;
        }
        return ARGB.color(XRay.meshAlpha(), ARGB.red(color), ARGB.green(color), ARGB.blue(color));
    }
}
