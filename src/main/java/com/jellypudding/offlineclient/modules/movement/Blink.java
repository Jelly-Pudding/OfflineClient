package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.lang.ref.WeakReference;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public final class Blink extends Module {

    private final NumberSetting limit = new NumberSetting("Limit",
        "Restarts after holding this many packets.",
        200, 20, 1000, 10, " packets");

    // Filled from the packet thread and read by the HUD.
    private final Queue<Packet<?>> held = new LinkedBlockingQueue<>();

    // The player the held packets were captured from. Weak to avoid pinning a dead world.
    private volatile WeakReference<LocalPlayer> owner = new WeakReference<>(null);

    public Blink() {
        super("Blink", "Pauses your position updates until you turn it off.",
            Category.MOVEMENT);
        addSettings(limit);
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        return held.size() + " held";
    }

    @Override
    protected void onEnable() {
        held.clear();
        owner = new WeakReference<>(mc.player);
    }

    // Packets sent by release come straight back through this handler.
    private boolean releasing;

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        LocalPlayer player = mc.player;
        if (releasing || player == null || !(event.getPacket() instanceof ServerboundMovePlayerPacket)) {
            return;
        }
        if (player != owner.get()) {
            // A respawn or a new world means the held positions are worthless.
            held.clear();
            owner = new WeakReference<>(player);
        }
        if (held.size() >= limit.getInt()) {
            // Toggling the module from the packet thread would edit the bus mid dispatch.
            release();
            owner = new WeakReference<>(player);
            return;
        }
        event.cancel();
        held.add(event.getPacket());
    }

    @Override
    protected void onDisable() {
        release();
    }

    // Sends everything held on to the server. Positions of a dead player are dropped.
    private void release() {
        LocalPlayer player = mc.player;
        LocalPlayer captured = owner.get();
        owner = new WeakReference<>(null);
        boolean replay = player != null && player == captured;
        releasing = true;
        try {
            Packet<?> packet;
            while ((packet = held.poll()) != null) {
                if (replay) {
                    player.connection.send(packet);
                }
            }
        } finally {
            releasing = false;
        }
    }
}
