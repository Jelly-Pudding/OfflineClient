package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.XRay;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * XRay hooks for solid blocks. Chunk meshing runs on worker threads and
 * the per block alpha lives in a thread local on the XRay module.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {

    @Shadow
    @Final
    private QuadInstance quadInstance;

    @Inject(
        method = {
            "tesselateFlat(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLjava/util/List;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V",
            "tesselateAmbientOcclusion(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLjava/util/List;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V"
        },
        at = @At("HEAD"),
        cancellable = true)
    private void onTesselate(BlockQuadOutput output, float x, float y, float z,
                             List<BlockStateModelPart> parts, BlockAndTintGetter level,
                             BlockState state, BlockPos pos, CallbackInfo ci) {
        XRay xray = XRay.get();
        if (xray == null || !xray.isEnabled()) {
            XRay.setMeshAlpha(-1);
            return;
        }
        int alpha = xray.alphaFor(level, state, pos);
        XRay.setMeshAlpha(alpha);
        if (alpha == 0) {
            ci.cancel();
        }
    }

    @Inject(
        method = "putQuadWithTint(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/resources/model/geometry/BakedQuad;)V",
        at = @At("HEAD"))
    private void onPutQuad(BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter level,
                           BlockState state, BlockPos pos, BakedQuad quad, CallbackInfo ci) {
        if (XRay.meshingTranslucent()) {
            quadInstance.multiplyColor(ARGB.color(XRay.meshAlpha(), 255, 255, 255));
        }
    }

    // Wanted blocks draw every face that does not touch another wanted block.
    @ModifyReturnValue(
        method = "shouldRenderFace(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;)Z",
        at = @At("RETURN"))
    private boolean onShouldRenderFace(boolean original, BlockAndTintGetter level, BlockState state,
                                       Direction direction, BlockPos neighborPos) {
        if (original) {
            return true;
        }
        XRay xray = XRay.get();
        if (xray == null || !xray.isEnabled() || !xray.isVisible(state.getBlock())) {
            return false;
        }
        return !xray.isVisible(level.getBlockState(neighborPos).getBlock());
    }
}
