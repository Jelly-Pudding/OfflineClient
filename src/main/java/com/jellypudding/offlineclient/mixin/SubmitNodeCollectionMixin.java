package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.ChestEsp;
import net.minecraft.client.renderer.SubmitNodeCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// A model submitted with an outline colour is drawn into the glow buffer as well.
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionMixin {

    // The outline colour is the fourth int argument after light and overlay and tint.
    @ModifyVariable(method = "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;"
        + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;III"
        + "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;I"
        + "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
        at = @At("HEAD"), argsOnly = true, ordinal = 3)
    private int onOutlineColor(int outline) {
        int glow = ChestEsp.currentGlow();
        return glow != 0 ? glow : outline;
    }
}
