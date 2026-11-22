package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSentEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;

// Paper kicks a player whose book edit comes within twenty of its ticks of the one before. It
// keeps one last edit for each player and counts the start of play as one. Every edit that leaves
// the client is noted here whatever sent it and the server ticks since are counted as they pass.
public final class BookPace {

    public static final BookPace INSTANCE = new BookPace();

    private static final int PAPER_TICKS = 20;
    // Server ticks of uneven length and a trip to the server that varies take up to this many more.
    private static final int SPARE_TICKS = 5;

    private final TickRate.Clock serverClock = new TickRate.Clock();
    private double sinceEdit;

    private BookPace() {
    }

    // A server that is not Paper takes an edit at any time. After a join the count waits until the
    // server's rate is known.
    public synchronized boolean ready() {
        return !ServerInfo.runsPaper() || TickRate.INSTANCE.measured() && sinceEdit >= PAPER_TICKS + SPARE_TICKS;
    }

    // First of all because a module asks for the count later in the same tick.
    @Subscribe(priority = Subscribe.FIRST)
    private synchronized void onTick(TickEvent event) {
        sinceEdit += serverClock.passed();
    }

    @Subscribe
    private void onPacketSent(PacketSentEvent event) {
        if (event.getPacket() instanceof ServerboundEditBookPacket) {
            edited();
        }
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundLoginPacket) {
            edited();
        }
    }

    private synchronized void edited() {
        sinceEdit = 0;
        serverClock.restart();
    }
}
