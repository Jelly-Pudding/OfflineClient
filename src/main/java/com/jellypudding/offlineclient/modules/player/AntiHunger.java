package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Tells the server the player is airborne so walking costs less hunger.
 * Pauses while falling so fall damage still applies at the right height.
 */
public final class AntiHunger extends Module {

    public AntiHunger() {
        super("AntiHunger", "Slows down how fast you get hungry.", Category.PLAYER);
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!inGame() || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet)) {
            return;
        }
        if (!packet.isOnGround() || mc.player.fallDistance > 0.5) {
            return;
        }
        // The server slows down mining a lot for airborne players. Leave the
        // packets alone while a block is being broken.
        if (mc.gameMode.isDestroying()) {
            return;
        }

        double x = packet.getX(mc.player.getX());
        double y = packet.getY(mc.player.getY());
        double z = packet.getZ(mc.player.getZ());
        float yaw = packet.getYRot(mc.player.getYRot());
        float pitch = packet.getXRot(mc.player.getXRot());
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
