package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.inventory.MenuType;

public final class PacketUtil {

    // The live connection. Set from the netty thread on every packet read.
    private static volatile Connection connection;

    // The ground claim whilst it is being sent and the tick it last went out.
    private static Packet<?> claim;
    private static int claimTick = -1;

    private PacketUtil() {
    }

    public static void noteConnection(Connection live) {
        connection = live;
    }

    // True whilst the connection speaks the game protocol. A server switch passes through
    // configuration on the same connection and some packet kinds belong to both.
    public static boolean inPlay() {
        ClientPacketListener listener = OfflineClient.MC.getConnection();
        return listener != null && listener.getConnection().getPacketListener() == listener;
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

    // True for a start or a stop. The server judges either against the last ground flag it heard.
    public static boolean judgesBreak(Packet<?> packet) {
        return packet instanceof ServerboundPlayerActionPacket action
            && (action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                || action.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
    }

    // A claim ends a glide and the server ignores one from a rider. It also lands the fall
    // the server holds and a fall past the safe distance would hurt.
    public static boolean mayClaimGround(LocalPlayer player) {
        return !player.isFallFlying() && !player.isPassenger()
            && player.fallDistance < DamageUtil.safeFall(player);
    }

    // Tells the server you stand on the ground without moving you. The server keeps the
    // claim until the next movement packet. One a tick covers every break before it.
    public static void claimGround(LocalPlayer player) {
        if (!mayClaimGround(player) || claimTick == MoveGate.tick()) {
            return;
        }
        claimTick = MoveGate.tick();
        claim = new ServerboundMovePlayerPacket.StatusOnly(true, player.horizontalCollision);
        try {
            player.connection.send(claim);
        } finally {
            claim = null;
        }
    }

    // True for the claim above. Modules that rewrite the ground flag leave it as it is.
    public static boolean isGroundClaim(Packet<?> packet) {
        return packet != null && packet == claim;
    }

    // Tells the server you are off the ground without moving you. It opens a glide only
    // for a player it takes to be in the air.
    public static void claimAir(LocalPlayer player) {
        player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(false, player.horizontalCollision));
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
