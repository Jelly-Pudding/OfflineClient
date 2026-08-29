package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.renderer.WorldBorderRenderer;
import net.minecraft.client.renderer.state.level.WorldBorderRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldBorderRenderer.class)
public abstract class WorldBorderRendererMixin {

    @Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WorldBorderRenderState;Lnet/minecraft/world/phys/Vec3;DD)V",
        at = @At("HEAD"), cancellable = true)
    private void onRender(WorldBorderRenderState state, Vec3 camera, double width, double height,
                          CallbackInfo ci) {
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.hidesWorldBorder()) {
            ci.cancel();
        }
    }
}
