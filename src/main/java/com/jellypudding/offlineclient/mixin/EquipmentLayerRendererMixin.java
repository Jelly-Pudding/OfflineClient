package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EquipmentLayerRenderer.class)
public abstract class EquipmentLayerRendererMixin {

    // Every worn piece on every entity goes through one of the two overloads.
    @Inject(method = "renderLayers", at = @At("HEAD"), cancellable = true)
    private void onRenderLayers(CallbackInfo ci) {
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.hidesArmour()) {
            ci.cancel();
        }
    }
}
