package com.jellypudding.offlineclient.rig.mixin;

import com.jellypudding.offlineclient.rig.core.Heard;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Lets the rig know how far the game has got with what a server sent.
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    // The network thread leaves the handler by an exception. Only the game's own thread reaches the end.
    @Inject(method = "handleItemCooldown", at = @At("TAIL"))
    private void rigHeard(ClientboundCooldownPacket packet, CallbackInfo ci) {
        Heard.took(packet.cooldownGroup());
    }

    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void rigJoined(ClientboundLoginPacket packet, CallbackInfo ci) {
        Heard.joined();
    }
}
