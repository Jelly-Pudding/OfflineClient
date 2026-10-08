package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.Proxy;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A server list ping that fails is tried a second way on a bare connection of its own. That one
// would skip the proxy and show the server your own address.
@Mixin(ServerStatusPinger.class)
public abstract class ServerStatusPingerMixin {

    @Inject(method = "pingLegacyServer", at = @At("HEAD"), cancellable = true)
    private void onPingLegacyServer(CallbackInfo ci) {
        if (Modules.active(Proxy.class) != null) {
            ci.cancel();
        }
    }
}
