package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Chams;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EndCrystalRenderer;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Chams resizes and recolours end crystals.
@Mixin(EndCrystalRenderer.class)
public abstract class EndCrystalRendererMixin {

    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"))
    private void onScale(EndCrystalRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                         CameraRenderState camera, CallbackInfo ci) {
        Chams chams = Chams.get();
        if (chams != null && chams.reshapesCrystals()) {
            float scale = chams.crystalScale();
            poseStack.scale(scale, scale, scale);
        }
    }

    @WrapOperation(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/resources/Identifier;IIILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"))
    private <S> void onSubmitModel(SubmitNodeCollector collector, Model<? super S> model, S state,
                                   PoseStack poseStack, Identifier texture, int light, int overlay,
                                   int outline, ModelFeatureRenderer.CrumblingOverlay crumbling,
                                   Operation<Void> original) {
        Chams chams = Chams.get();
        if (chams == null || !chams.reshapesCrystals()) {
            original.call(collector, model, state, poseStack, texture, light, overlay, outline, crumbling);
            return;
        }
        collector.submitModel(model, state, poseStack,
            RenderTypes.entityTranslucent(chams.crystalTexture(texture)),
            light, overlay, chams.crystalColor(), null, outline, crumbling);
    }
}
