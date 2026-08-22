package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.render.NoHurtCam;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    /** The whole hurt tilt is one call. Skipping it leaves the view steady. */
    @Inject(method = "bobHurt(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
        at = @At("HEAD"), cancellable = true)
    private void onBobHurt(CameraRenderState camera, PoseStack poseStack, CallbackInfo ci) {
        if (OfflineClient.INSTANCE.getModuleManager() == null) {
            return;
        }
        if (OfflineClient.INSTANCE.getModuleManager().get(NoHurtCam.class).isEnabled()) {
            ci.cancel();
        }
    }
}
