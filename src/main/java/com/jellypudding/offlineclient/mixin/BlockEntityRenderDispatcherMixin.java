package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.render.XRay;
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

    /** Chests and other block entities vanish with the block XRay hides. */
    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("HEAD"),
        cancellable = true)
    private <S extends BlockEntityRenderState> void onSubmit(S state, PoseStack poseStack,
                                                              SubmitNodeCollector collector,
                                                              CameraRenderState camera, CallbackInfo ci) {
        XRay xray = XRay.get();
        if (xray == null || !xray.isEnabled() || state.blockPos == null || OfflineClient.MC.level == null) {
            return;
        }
        if (xray.alphaFor(OfflineClient.MC.level, OfflineClient.MC.level.getBlockState(state.blockPos), state.blockPos) == 0) {
            ci.cancel();
        }
    }
}
