package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.SoundBlocker;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    // Every sound the game plays passes through here whether the server asked for it or not.
    @Inject(
        method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)"
            + "Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
        at = @At("HEAD"),
        cancellable = true)
    private void onPlay(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        SoundBlocker blocker = Modules.get(SoundBlocker.class);
        if (blocker != null && blocker.shouldMute(instance)) {
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }
}
