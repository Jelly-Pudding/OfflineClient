package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.EntityAddedEvent;
import com.jellypudding.offlineclient.modules.player.NoRotate;
import com.jellypudding.offlineclient.modules.render.NewChunks;
import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    // Dropped on the network thread before the packet is handed to the main thread.
    @Inject(method = "handleAddEntity(Lnet/minecraft/network/protocol/game/ClientboundAddEntityPacket;)V",
        at = @At("HEAD"), cancellable = true)
    private void onDropAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.dropsSpawnPacket(packet.getType())) {
            ci.cancel();
        }
    }

    // Runs once on the network thread to bounce to the main thread. Only the main run has the entity.
    @Inject(method = "handleAddEntity(Lnet/minecraft/network/protocol/game/ClientboundAddEntityPacket;)V",
        at = @At("TAIL"))
    private void onAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        if (!OfflineClient.MC.isSameThread() || OfflineClient.MC.level == null) {
            return;
        }
        Entity entity = OfflineClient.MC.level.getEntity(packet.getId());
        if (entity != null) {
            OfflineClient.INSTANCE.getEventBus().post(new EntityAddedEvent(entity));
        }
    }

    @Unique
    private float offlineclient$savedYaw;
    @Unique
    private float offlineclient$savedPitch;

    // Packet handlers run once on the network thread to bounce onto the main thread.
    // Only the main thread run touches the player.
    @Unique
    private boolean offlineclient$noRotateActive() {
        if (!OfflineClient.MC.isSameThread() || OfflineClient.MC.player == null) {
            return false;
        }
        return Modules.enabled(NoRotate.class);
    }

    @Unique
    private void offlineclient$saveRotation() {
        LocalPlayer player = OfflineClient.MC.player;
        offlineclient$savedYaw = player.getYRot();
        offlineclient$savedPitch = player.getXRot();
    }

    // The tiny offset makes the next position update carry a rotation.
    @Unique
    private void offlineclient$restoreRotation() {
        LocalPlayer player = OfflineClient.MC.player;
        player.setYRot(offlineclient$savedYaw + 0.000001f);
        player.setXRot(offlineclient$savedPitch + 0.000001f);
        player.yHeadRot = offlineclient$savedYaw;
        player.yBodyRot = offlineclient$savedYaw;
    }

    // The chunk is in the world and every later packet is still queued behind this one.
    // NewChunks reads the liquid here whilst the data is untouched.
    @Inject(
        method = "handleLevelChunkWithLight"
            + "(Lnet/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket;)V",
        at = @At("TAIL"))
    private void onChunkLoaded(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        NewChunks newChunks = Modules.get(NewChunks.class);
        if (newChunks != null) {
            newChunks.onChunkLoaded(packet.getX(), packet.getZ());
        }
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
