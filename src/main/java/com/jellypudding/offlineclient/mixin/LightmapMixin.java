package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.XRay;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Lightmap.class)
public abstract class LightmapMixin {

    @Shadow
    @Final
    private GpuTexture texture;

    // The vanilla lightmap comes back on its own the next time it updates.
    @Inject(
        method = "render(Lnet/minecraft/client/renderer/state/LightmapRenderState;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void onRender(LightmapRenderState state, CallbackInfo ci) {
        XRay xray = XRay.get();
        if (xray == null || !xray.isEnabled()) {
            return;
        }
        RenderSystem.getDevice().createCommandEncoder()
            .clearColorTexture(texture, new Vector4f(1f, 1f, 1f, 1f));
        ci.cancel();
    }
}
