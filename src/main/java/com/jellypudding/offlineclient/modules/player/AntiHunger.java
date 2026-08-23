package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.RotationManager;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Sends movement packets with the on ground flag cleared. The server charges
 * no walking exhaustion and no fall damage to airborne players.
 */
public final class AntiHunger extends Module {

    public AntiHunger() {
        super("AntiHunger", "Slows down how fast you get hungry.", Category.PLAYER);
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket packet) || !packet.isOnGround()) {
            return;
        }
        // The packet thread can drop the player and the game mode mid handler.
        LocalPlayer player = mc.player;
        MultiPlayerGameMode gameMode = mc.gameMode;
        if (player == null || gameMode == null || player.fallDistance > 0.5) {
            return;
        }
        // The server slows mining a lot for airborne players.
        if (gameMode.isDestroying()) {
            return;
        }

        double x = packet.getX(player.getX());
        double y = packet.getY(player.getY());
        double z = packet.getZ(player.getZ());
        float yaw = packet.getYRot(RotationManager.getServerYaw());
        float pitch = packet.getXRot(RotationManager.getServerPitch());
        boolean collision = packet.horizontalCollision();

        if (packet.hasPosition() && packet.hasRotation()) {
            event.setPacket(new ServerboundMovePlayerPacket.PosRot(x, y, z, yaw, pitch, false, collision));
        } else if (packet.hasPosition()) {
            event.setPacket(new ServerboundMovePlayerPacket.Pos(x, y, z, false, collision));
        } else if (packet.hasRotation()) {
            event.setPacket(new ServerboundMovePlayerPacket.Rot(yaw, pitch, false, collision));
        } else {
            event.setPacket(new ServerboundMovePlayerPacket.StatusOnly(false, collision));
        }
    }
}
