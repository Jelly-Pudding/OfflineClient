package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.inventory.MenuType;

public final class PacketUtil {

    // The live connection. Set from the netty thread on every packet read.
    private static volatile Connection connection;

    private PacketUtil() {
    }

    public static void noteConnection(Connection live) {
        connection = live;
    }

    // A click on an anvil already down makes the server open its repair menu. Closing it on
    // the server as well keeps the next click from being swallowed.
    public static void closeAnvilMenu(PacketReceiveEvent event) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null && event.getPacket() instanceof ClientboundOpenScreenPacket packet
            && packet.getType() == MenuType.ANVIL) {
            event.cancel();
            player.connection.send(new ServerboundContainerClosePacket(packet.getContainerId()));
        }
    }

    // Sends straight down the wire. Works before a world exists.
    public static void send(Packet<?> packet) {
        Connection live = connection;
        if (live != null && live.isConnected()) {
            live.send(packet);
        }
    }

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

    // A packet that carried no position is upgraded to the form that does.
    public static ServerboundMovePlayerPacket withPosition(ServerboundMovePlayerPacket packet,
                                                           LocalPlayer player, double x, double y,
                                                           double z, boolean onGround) {
        boolean collision = packet.horizontalCollision();
        if (packet.hasRotation()) {
            return new ServerboundMovePlayerPacket.PosRot(x, y, z,
                packet.getYRot(player.getYRot()), packet.getXRot(player.getXRot()),
                onGround, collision);
        }
        return new ServerboundMovePlayerPacket.Pos(x, y, z, onGround, collision);
    }

    // A packet that carried no rotation is upgraded to the form that does.
    public static ServerboundMovePlayerPacket withRotation(ServerboundMovePlayerPacket packet,
                                                           LocalPlayer player, float yaw, float pitch) {
        boolean onGround = packet.isOnGround();
        boolean collision = packet.horizontalCollision();

        if (packet.hasPosition()) {
            return new ServerboundMovePlayerPacket.PosRot(
                packet.getX(player.getX()), packet.getY(player.getY()), packet.getZ(player.getZ()),
                yaw, pitch, onGround, collision);
        }
        return new ServerboundMovePlayerPacket.Rot(yaw, pitch, onGround, collision);
    }
}
