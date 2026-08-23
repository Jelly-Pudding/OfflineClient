package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.NoHurtCam;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    // The whole hurt tilt is one call.
    @Inject(method = "bobHurt(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
        at = @At("HEAD"), cancellable = true)
    private void onBobHurt(CameraRenderState camera, PoseStack poseStack, CallbackInfo ci) {
        if (Modules.enabled(NoHurtCam.class)) {
            ci.cancel();
        }
    }
}
