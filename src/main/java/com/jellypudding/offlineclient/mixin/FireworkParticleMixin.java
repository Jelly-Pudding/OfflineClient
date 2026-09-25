package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.util.Modules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The burst is made of sparks and a flash overlay. Neither is reachable by
// name from here which is why both are named as strings.
@Mixin(targets = {
    "net.minecraft.client.particle.FireworkParticles$SparkParticle",
    "net.minecraft.client.particle.FireworkParticles$OverlayParticle"
})
public abstract class FireworkParticleMixin {

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void onExtract(CallbackInfo ci) {
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.hidesFireworks()) {
            ci.cancel();
        }
    }
}
