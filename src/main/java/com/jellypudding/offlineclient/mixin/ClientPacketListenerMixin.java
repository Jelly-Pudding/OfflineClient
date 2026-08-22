package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.player.NoRotate;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Unique
    private float offlineclient$savedYaw;
    @Unique
    private float offlineclient$savedPitch;

    /**
     * Packet handlers run once on the network thread just to bounce onto
     * the main thread. Only the main thread run touches the player.
     */
    @Unique
    private boolean offlineclient$noRotateActive() {
        if (!OfflineClient.MC.isSameThread() || OfflineClient.MC.player == null) {
            return false;
        }
        if (OfflineClient.INSTANCE.getModuleManager() == null) {
            return false;
        }
        return OfflineClient.INSTANCE.getModuleManager().get(NoRotate.class).isEnabled();
    }

    @Unique
    private void offlineclient$saveRotation() {
        LocalPlayer player = OfflineClient.MC.player;
        offlineclient$savedYaw = player.getYRot();
        offlineclient$savedPitch = player.getXRot();
    }

    /**
     * Puts the saved look angles back. The tiny offset makes the next
     * position update carry a rotation.
     */
    @Unique
    private void offlineclient$restoreRotation() {
        LocalPlayer player = OfflineClient.MC.player;
        player.setYRot(offlineclient$savedYaw + 0.000001f);
        player.setXRot(offlineclient$savedPitch + 0.000001f);
        player.yHeadRot = offlineclient$savedYaw;
        player.yBodyRot = offlineclient$savedYaw;
    }

    @Inject(
        method = "handleMovePlayer(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V",
        at = @At("HEAD"))
    private void onMovePlayerHead(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (offlineclient$noRotateActive()) {
            offlineclient$saveRotation();
        }
    }

    @Inject(
        method = "handleMovePlayer(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V",
        at = @At("RETURN"))
    private void onMovePlayerReturn(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (offlineclient$noRotateActive()) {
            offlineclient$restoreRotation();
        }
    }

    @Inject(
        method = "handleRotatePlayer(Lnet/minecraft/network/protocol/game/ClientboundPlayerRotationPacket;)V",
        at = @At("HEAD"))
    private void onRotatePlayerHead(ClientboundPlayerRotationPacket packet, CallbackInfo ci) {
        if (offlineclient$noRotateActive()) {
            offlineclient$saveRotation();
        }
    }

    @Inject(
        method = "handleRotatePlayer(Lnet/minecraft/network/protocol/game/ClientboundPlayerRotationPacket;)V",
        at = @At("RETURN"))
    private void onRotatePlayerReturn(ClientboundPlayerRotationPacket packet, CallbackInfo ci) {
        if (offlineclient$noRotateActive()) {
            offlineclient$restoreRotation();
        }
    }
}
