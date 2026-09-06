package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Blur;
import com.jellypudding.offlineclient.modules.render.ItemEsp;
import com.jellypudding.offlineclient.modules.render.NoHurtCam;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
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

    // ItemESP holds the view still so its tracers do not wobble.
    @Inject(method = "bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
        at = @At("HEAD"), cancellable = true)
    private void onBobView(CameraRenderState camera, PoseStack poseStack, CallbackInfo ci) {
        ItemEsp items = Modules.get(ItemEsp.class);
        if (items != null && items.holdsViewStill()) {
            ci.cancel();
        }
    }

    // The menu blur radius comes from the Blur module in place of the video option.
    @ModifyArg(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJLnet/minecraft/client/DeltaTracker;ILnet/minecraft/world/phys/Vec3;Z)V"),
        index = 5)
    private int onBlurRadius(int radius) {
        Blur blur = Modules.get(Blur.class);
        return blur == null ? radius : blur.radius(radius);
    }
}
