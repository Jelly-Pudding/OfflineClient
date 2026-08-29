package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererMixin {

    // Every enchanted item in the world and in hand and in a screen asks here.
    @ModifyExpressionValue(method = "prepareFoilSubmit",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;foilType()"
                + "Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;"))
    private ItemStackRenderState.FoilType onFoilType(ItemStackRenderState.FoilType original) {
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.hidesGlint()) {
            return ItemStackRenderState.FoilType.NONE;
        }
        return original;
    }
}
