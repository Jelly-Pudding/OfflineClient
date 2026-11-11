package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.Playlist;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {

    // The game picks no song of its own whilst Playlist plays the ones you chose.
    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void onTick(CallbackInfo ci) {
        if (Modules.active(Playlist.class) != null) {
            ci.cancel();
        }
    }
}
