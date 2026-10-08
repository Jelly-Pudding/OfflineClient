package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.config.NameStore;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// A server that checks accounts looks up your account and then the name the hello gave. A saved name
// in use fails that second look and the login is refused.
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakePacketListenerImplMixin {

    @Shadow
    @Final
    private ServerData serverData;

    // Null when your account passed. Runs on a worker thread. A realm always gets the account name.
    @Inject(method = "authenticateServer(Ljava/lang/String;)Lnet/minecraft/network/chat/Component;",
        at = @At("RETURN"))
    private void onAuthenticated(CallbackInfoReturnable<Component> cir) {
        if (cir.getReturnValue() == null && (serverData == null || !serverData.isRealm())) {
            NameStore.get().accountChecked();
        }
    }

    @Inject(method = "handleLoginFinished(Lnet/minecraft/network/protocol/login/ClientboundLoginFinishedPacket;)V",
        at = @At("HEAD"))
    private void onLoginFinished(CallbackInfo ci) {
        NameStore.get().clearChecked();
    }
}
