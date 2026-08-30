package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.util.Modules;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.object.banner.BannerModel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.blockentity.state.BannerRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.util.Unit;
import net.minecraft.world.level.block.BannerBlock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BannerRenderer.class)
public abstract class BannerRendererMixin {

    @Unique
    private static final String SUBMIT = "submit(Lnet/minecraft/client/renderer/blockentity/state/BannerRenderState;"
        + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
        + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";

    @Shadow
    @Final
    private SpriteGetter sprites;

    @Shadow
    protected abstract BannerModel bannerModel(BannerBlock.AttachmentType type);

    @Inject(method = SUBMIT, at = @At("HEAD"), cancellable = true)
    private void onSubmit(BannerRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                          CameraRenderState camera, CallbackInfo ci) {
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender == null) {
            return;
        }
        switch (noRender.bannerMode()) {
            case HIDE -> ci.cancel();
            case POLE_ONLY -> {
                // The pole model is the bar and post without the cloth.
                poseStack.pushPose();
                poseStack.mulPose(state.transformation);
                collector.submitModel(bannerModel(state.attachmentType), Unit.INSTANCE, poseStack,
                    state.lightCoords, OverlayTexture.NO_OVERLAY, -1, Sheets.BANNER_BASE, sprites,
                    0, state.breakProgress);
                poseStack.popPose();
                ci.cancel();
            }
            case SHOW -> {
            }
        }
    }
}
