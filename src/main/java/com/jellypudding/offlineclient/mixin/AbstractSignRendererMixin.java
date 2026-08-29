package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSignRenderer.class)
public abstract class AbstractSignRendererMixin {

    // The board is still drawn. Only the lines on it are skipped.
    @Inject(method = "submitSignText", at = @At("HEAD"), cancellable = true)
    private void onSubmitSignText(CallbackInfo ci) {
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.hidesSignText()) {
            ci.cancel();
        }
    }
}
