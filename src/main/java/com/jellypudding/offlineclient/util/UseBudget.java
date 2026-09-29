package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.ArrayDeque;
import java.util.Deque;

// Paper throws away every use packet past the ninth in 300 milliseconds and never answers
// it. Each one the client sends is counted here and a builder asks how many more will land.
public final class UseBudget {

    public static final UseBudget INSTANCE = new UseBudget();

    // Paper's spam limiter as it ships. A click on a block and a use in the air share the count.
    private static final int PAPER_USES = 9;
    private static final long PAPER_WINDOW_MS = 300;
    // Packets reach the server a little bunched after the trip. The window is kept this much wider.
    private static final long SPREAD_MS = 50;
    private static final long WINDOW_MS = PAPER_WINDOW_MS + SPREAD_MS;

    private final Deque<Long> sent = new ArrayDeque<>();

    private UseBudget() {
    }

    // Last of all because a packet another handler cancels never went out.
    @Subscribe(priority = Subscribe.LAST)
    private void onPacketSend(PacketSendEvent event) {
        Packet<?> packet = event.getPacket();
        if (!event.isCancelled()
            && (packet instanceof ServerboundUseItemOnPacket || packet instanceof ServerboundUseItemPacket)) {
            record();
        }
    }

    private synchronized void record() {
        long now = System.currentTimeMillis();
        forgetBefore(now);
        sent.addLast(now);
    }

    private synchronized int left() {
        forgetBefore(System.currentTimeMillis());
        return Math.max(0, PAPER_USES - sent.size());
    }

    private void forgetBefore(long now) {
        while (!sent.isEmpty() && now - sent.peekFirst() >= WINDOW_MS) {
            sent.removeFirst();
        }
    }

    // How many more use packets the server takes right now. Servers without the limiter take any number.
    public static int remaining() {
        return limited() ? INSTANCE.left() : Integer.MAX_VALUE;
    }

    // Paper and its forks run the limiter. A server that has not named itself yet counts as one.
    private static boolean limited() {
        return ServerInfo.brand() == null || ServerInfo.runsPaper();
    }
}
