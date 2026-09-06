package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.renderer.blockentity.SpawnerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpawnerRenderer.class)
public abstract class SpawnerRendererMixin {

    @Inject(method = "submitEntityInSpawner", at = @At("HEAD"), cancellable = true)
    private static void onSubmitEntityInSpawner(CallbackInfo ci) {
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.hidesSpawnerMobs()) {
            ci.cancel();
        }
    }
}
