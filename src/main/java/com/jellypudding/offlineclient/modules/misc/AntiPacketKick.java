package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

// Paces outgoing packets. Anything the connection depends on is let straight through.
public final class AntiPacketKick extends Module {

    private static final long WINDOW_MILLIS = 1000;

    private final NumberSetting limit = new NumberSetting("Limit",
        "Packets allowed out each second before the rest wait.", 300, 50, 1000, 10, " per second")
        .min(20);
    private final NumberSetting queueSize = new NumberSetting("Queue",
        "Most packets held back at once.",
        200, 20, 1000, 10, " packets").min(1);
    private final BoolSetting notify = new BoolSetting("Notify",
        "Say in chat the first time a burst gets paced.", true);

    // Filled from the netty thread and drained on the main thread.
    private final ConcurrentLinkedQueue<Packet<?>> held = new ConcurrentLinkedQueue<>();
    private final AtomicInteger heldCount = new AtomicInteger();
    private final AtomicInteger sent = new AtomicInteger();

    private long windowStart;
    private int rate;
    private boolean warned;
    // The thread draining the queue. Only its own sends skip the cap.
    private volatile Thread drainer;

    public AntiPacketKick() {
        super("AntiPacketKick", "Spreads packet bursts out so the server does not drop you.",
            Category.MISC);
        addSettings(limit, queueSize, notify);
        searchTags("flood", "throttle", "rate limit");
    }

    @Override
    public String getSuffix() {
        int waiting = heldCount.get();
        return waiting > 0 ? rate + "/s " + waiting + " held" : rate + "/s";
    }

    @Override
    protected void onEnable() {
        held.clear();
        heldCount.set(0);
        sent.set(0);
        windowStart = System.currentTimeMillis();
        rate = 0;
        warned = false;
    }

    @Override
    protected void onDisable() {
        release(Integer.MAX_VALUE);
        held.clear();
        heldCount.set(0);
    }

    @Subscribe(priority = -100)
    private void onPacketSend(PacketSendEvent event) {
        if (Thread.currentThread() == drainer || event.isCancelled()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (isCritical(packet)) {
            sent.incrementAndGet();
            return;
        }
        if (sent.get() < limit.getInt() && heldCount.get() == 0) {
            sent.incrementAndGet();
            return;
        }
        event.cancel();
        held.add(packet);
        if (heldCount.incrementAndGet() > queueSize.getInt() && held.poll() != null) {
            heldCount.decrementAndGet();
        }
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        long now = System.currentTimeMillis();
        if (now - windowStart >= WINDOW_MILLIS) {
            windowStart = now;
            rate = sent.getAndSet(0);
        }
        int waiting = heldCount.get();
        if (waiting == 0) {
            return;
        }
        if (notify.isOn() && !warned) {
            warned = true;
            ChatUtil.message("§eHolding packets back to stay under " + limit.getInt() + " per second.");
        }
        release(limit.getInt() - sent.get());
    }

    private void release(int allowance) {
        if (allowance <= 0 || mc.player == null) {
            return;
        }
        ClientPacketListener connection = mc.player.connection;
        drainer = Thread.currentThread();
        try {
            for (int i = 0; i < allowance; i++) {
                Packet<?> packet = held.poll();
                if (packet == null) {
                    break;
                }
                heldCount.decrementAndGet();
                connection.send(packet);
                sent.incrementAndGet();
            }
        } finally {
            drainer = null;
        }
    }

    private static boolean isCritical(Packet<?> packet) {
        return packet instanceof ServerboundKeepAlivePacket
            || packet instanceof ServerboundPongPacket
            || packet instanceof ServerboundAcceptTeleportationPacket
            || packet instanceof ServerboundMovePlayerPacket
            || packet instanceof ServerboundMoveVehiclePacket
            || packet instanceof ServerboundClientTickEndPacket
            || packet instanceof ServerboundChunkBatchReceivedPacket
            || packet instanceof ServerboundPlayerLoadedPacket
            || packet instanceof ServerboundClientCommandPacket
            || packet instanceof ServerboundClientInformationPacket
            || packet instanceof ServerboundContainerClosePacket
            || packet instanceof ServerboundResourcePackPacket
            || packet instanceof ServerboundCustomPayloadPacket
            || packet instanceof ServerboundChatPacket
            || packet instanceof ServerboundChatAckPacket
            || packet instanceof ServerboundChatCommandPacket
            || packet instanceof ServerboundChatCommandSignedPacket;
    }
}
