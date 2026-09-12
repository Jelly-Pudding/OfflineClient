package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.ClearView;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {

    // True between the push before the flames and the pop after them.
    @Unique
    private static boolean offlineclient$loweredFire;

    @Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
    private static void onSubmitFire(PoseStack poseStack, SubmitNodeCollector collector,
                                     TextureAtlasSprite sprite, CallbackInfo ci) {
        ClearView clearView = Modules.active(ClearView.class);
        if (clearView == null) {
            return;
        }
        if (clearView.blocksFire()) {
            ci.cancel();
        } else if (clearView.fireOffset() > 0) {
            offlineclient$loweredFire = true;
            poseStack.pushPose();
            poseStack.translate(0, -clearView.fireOffset(), 0);
        }
    }

    @Inject(method = "submitFire", at = @At("TAIL"))
    private static void afterSubmitFire(PoseStack poseStack, SubmitNodeCollector collector,
                                        TextureAtlasSprite sprite, CallbackInfo ci) {
        if (offlineclient$loweredFire) {
            offlineclient$loweredFire = false;
            poseStack.popPose();
        }
    }

    @Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
    private static void onSubmitWater(Minecraft minecraft, PoseStack poseStack,
                                      SubmitNodeCollector collector, CallbackInfo ci) {
        ClearView clearView = Modules.active(ClearView.class);
        if (clearView != null && clearView.blocksWater()) {
            ci.cancel();
        }
    }

    @Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
    private static void onSubmitBlockSprite(TextureAtlasSprite sprite, PoseStack poseStack,
                                            SubmitNodeCollector collector, int light,
                                            CallbackInfo ci) {
        ClearView clearView = Modules.active(ClearView.class);
        if (clearView != null && clearView.blocksBlockInFace()) {
            ci.cancel();
        }
    }

    // The totem pop and the trial key animation come through here.
    @Inject(method = "renderItemActivationAnimation", at = @At("HEAD"), cancellable = true)
    private void onItemActivation(PoseStack poseStack, float partialTick,
                                  SubmitNodeCollector collector, CallbackInfo ci) {
        ClearView clearView = Modules.active(ClearView.class);
        if (clearView != null && clearView.blocksTotemPop()) {
            ci.cancel();
        }
    }
}
