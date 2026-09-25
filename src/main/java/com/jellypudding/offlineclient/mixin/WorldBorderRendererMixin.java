package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.renderer.WorldBorderRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldBorderRenderer.class)
public abstract class WorldBorderRendererMixin {

    @Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WorldBorderRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/world/phys/Vec3;D)V",
        at = @At("HEAD"), cancellable = true)
    private void onRender(CallbackInfo ci) {
        if (offlineclient$hidden()) {
            ci.cancel();
        }
    }

    // The same wall drawn again for whatever shows through it.
    @Inject(method = "renderOit(Lnet/minecraft/client/renderer/state/level/WorldBorderRenderState;Lnet/minecraft/world/phys/Vec3;DLnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
        at = @At("HEAD"), cancellable = true)
    private void onRenderOit(CallbackInfo ci) {
        if (offlineclient$hidden()) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean offlineclient$hidden() {
        NoRender noRender = Modules.get(NoRender.class);
        return noRender != null && noRender.hidesWorldBorder();
    }
}
