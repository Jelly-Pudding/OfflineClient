package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.Linger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl$CommonDialogAccess")
public abstract class CommonDialogAccessMixin {

    // The player chose to leave through a screen the server showed. The game ends the connection at
    // once and Linger must not keep it.
    @Inject(method = "disconnect(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"))
    private void onDisconnect(CallbackInfo ci) {
        Linger.expectLeave();
    }
}
