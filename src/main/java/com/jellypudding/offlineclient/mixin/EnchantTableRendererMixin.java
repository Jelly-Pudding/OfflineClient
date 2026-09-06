package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.EnchantTableRenderer;
import net.minecraft.client.renderer.blockentity.state.EnchantTableRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The table itself is an ordinary block model. This renderer only draws the book.
@Mixin(EnchantTableRenderer.class)
public abstract class EnchantTableRendererMixin {

    @Inject(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/EnchantTableRenderState;"
        + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
        + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("HEAD"), cancellable = true)
    private void onSubmit(EnchantTableRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                          CameraRenderState camera, CallbackInfo ci) {
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.hidesEnchantingBook()) {
            ci.cancel();
        }
    }
}
