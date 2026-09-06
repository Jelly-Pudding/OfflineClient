package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Chams;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Chams paints your first person arm.
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    @ModifyExpressionValue(
        method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Lnet/minecraft/client/model/geom/ModelPart;Z)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;entityTranslucent(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"))
    private RenderType onHandType(RenderType original, PoseStack poseStack, SubmitNodeCollector collector,
                                  int light, Identifier skin, ModelPart arm, boolean sleeve) {
        Chams chams = Chams.get();
        if (chams == null || !chams.paintsHand()) {
            return original;
        }
        return RenderTypes.entityTranslucent(chams.handTexture(skin));
    }

    @WrapOperation(
        method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Lnet/minecraft/client/model/geom/ModelPart;Z)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"))
    private void onSubmitHand(SubmitNodeCollector collector, ModelPart part, PoseStack poseStack,
                              RenderType type, int light, int overlay, TextureAtlasSprite sprite,
                              Operation<Void> original) {
        Chams chams = Chams.get();
        if (chams == null || !chams.paintsHand()) {
            original.call(collector, part, poseStack, type, light, overlay, sprite);
            return;
        }
        collector.submitModelPart(part, poseStack, type, light, overlay, sprite, chams.handColor(), null);
    }
}
