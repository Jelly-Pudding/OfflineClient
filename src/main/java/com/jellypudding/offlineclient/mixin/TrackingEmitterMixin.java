package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Confetti;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.particle.TrackingEmitter;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TrackingEmitter.class)
public abstract class TrackingEmitterMixin {

    @Shadow
    @Final
    private Entity entity;

    @Shadow
    @Final
    private ParticleOptions particleType;

    // Each tick sprays a handful of particles around the entity through the level.
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void onTickStart(CallbackInfo ci) {
        Confetti confetti = Modules.active(Confetti.class);
        if (confetti != null) {
            confetti.beginBurst(entity, particleType);
        }
    }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void onTickEnd(CallbackInfo ci) {
        Confetti confetti = Modules.active(Confetti.class);
        if (confetti != null) {
            confetti.endBurst();
        }
    }
}
