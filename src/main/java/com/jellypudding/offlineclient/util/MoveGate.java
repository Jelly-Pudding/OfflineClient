package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

// A 26.3 server disconnects anyone whose second movement packet of a tick
// carries a position. Every module that moves the player books its one position
// a tick here. A packet without a position is exempt and still raises the
// distance the server will accept from the next one.
public enum MoveGate {
    INSTANCE;

    // The most fillers the server counts towards the allowance.
    private static final int MAX_FILLERS = 4;

    private static volatile boolean spent;

    public static boolean free() {
        return !spent;
    }

    public static boolean send(double x, double y, double z, boolean onGround) {
        LocalPlayer player = OfflineClient.MC.player;
        if (spent || player == null) {
            return false;
        }
        player.connection.send(new ServerboundMovePlayerPacket.Pos(
            x, y, z, onGround, player.horizontalCollision));
        return true;
    }

    // Buys the next position packet a longer reach of sqrt(100 * count + 100) blocks.
    public static void fillers(int count) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        for (int i = Math.min(count, MAX_FILLERS); i > 0; i--) {
            player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(
                player.onGround(), player.horizontalCollision));
        }
    }

    @Subscribe(priority = 1000)
    private void onPacketSendFirst(PacketSendEvent event) {
        if (spent && event.getPacket() instanceof ServerboundMovePlayerPacket packet
            && packet.hasPosition()) {
            event.cancel();
        }
    }

    // Lowest priority because only the packet that survives every rewrite
    // counts. A rewrite can add a position the early check never saw.
    @Subscribe(priority = -1000)
    private void onPacketSendLast(PacketSendEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (event.getPacket() instanceof ServerboundMovePlayerPacket packet) {
            if (!packet.hasPosition()) {
                return;
            }
            if (spent) {
                event.cancel();
                return;
            }
            spent = true;
        } else if (event.getPacket() instanceof ServerboundClientTickEndPacket) {
            spent = false;
        }
    }

    // A paused game sends no tick end packet and would wedge the gate shut.
    @Subscribe(priority = -1000)
    private void onClientTick(ClientTickEvent event) {
        spent = false;
    }
}
