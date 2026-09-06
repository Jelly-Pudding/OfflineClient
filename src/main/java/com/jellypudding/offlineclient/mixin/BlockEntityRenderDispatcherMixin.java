package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.render.ChestEsp;
import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.modules.render.XRay;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    // Chests and other block entities vanish with the block XRay hides.
    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("HEAD"),
        cancellable = true)
    private <S extends BlockEntityRenderState> void onSubmit(S state, PoseStack poseStack,
                                                              SubmitNodeCollector collector,
                                                              CameraRenderState camera, CallbackInfo ci) {
        if (state.blockPos == null || OfflineClient.MC.level == null) {
            return;
        }
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.hidesBlockEntity(
            OfflineClient.MC.level.getBlockState(state.blockPos).getBlock())) {
            ci.cancel();
            return;
        }
        if (XRay.meshAlphaFor(OfflineClient.MC.level,
            OfflineClient.MC.level.getBlockState(state.blockPos), state.blockPos) == 0) {
            ci.cancel();
            return;
        }
        ChestEsp chestEsp = Modules.get(ChestEsp.class);
        ChestEsp.setCurrentGlow(chestEsp == null ? 0 : chestEsp.glowFor(state.blockPos));
    }

    // The glow colour only lasts for the one submit it was set for.
    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("RETURN"))
    private <S extends BlockEntityRenderState> void onSubmitEnd(S state, PoseStack poseStack,
                                                                 SubmitNodeCollector collector,
                                                                 CameraRenderState camera, CallbackInfo ci) {
        ChestEsp.setCurrentGlow(0);
    }
}
