package com.jellypudding.offlineclient.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Helpers for rewriting outgoing packets.
 */
public final class PacketUtil {

    private PacketUtil() {
    }

    /** The same movement packet with the on ground flag replaced. */
    public static ServerboundMovePlayerPacket withOnGround(ServerboundMovePlayerPacket packet,
                                                           LocalPlayer player, boolean onGround) {
        double x = packet.getX(player.getX());
        double y = packet.getY(player.getY());
        double z = packet.getZ(player.getZ());
        float yaw = packet.getYRot(player.getYRot());
        float pitch = packet.getXRot(player.getXRot());
        boolean collision = packet.horizontalCollision();

        if (packet.hasPosition() && packet.hasRotation()) {
            return new ServerboundMovePlayerPacket.PosRot(x, y, z, yaw, pitch, onGround, collision);
        }
        if (packet.hasPosition()) {
            return new ServerboundMovePlayerPacket.Pos(x, y, z, onGround, collision);
        }
        if (packet.hasRotation()) {
            return new ServerboundMovePlayerPacket.Rot(yaw, pitch, onGround, collision);
        }
        return new ServerboundMovePlayerPacket.StatusOnly(onGround, collision);
    }
}
