package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The burst is made of sparks and a flash overlay. Both are private classes and both are skipped.
@Mixin(targets = {
    "net.minecraft.client.particle.FireworkParticles$SparkParticle",
    "net.minecraft.client.particle.FireworkParticles$OverlayParticle"
})
public abstract class FireworkParticleMixin {

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void onExtract(QuadParticleRenderState state, Camera camera, float partialTicks,
                           CallbackInfo ci) {
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.hidesFireworks()) {
            ci.cancel();
        }
    }
}
