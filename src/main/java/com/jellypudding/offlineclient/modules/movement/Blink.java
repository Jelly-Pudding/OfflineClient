package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.util.ArrayList;
import java.util.List;

public final class Blink extends Module {

    private final NumberSetting limit = new NumberSetting("Limit",
        "Restarts after holding this many packets so the server never snaps you back too far.",
        200, 20, 1000, 10, " packets");

    private final List<Packet<?>> held = new ArrayList<>();
    private ClientPacketListener connection;

    public Blink() {
        super("Blink", "Pauses your position updates. You catch up to your real spot when you turn it off.",
            Category.MOVEMENT);
        addSettings(limit);
    }

    /** Never comes back on at launch. */
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
        connection = mc.player != null ? mc.player.connection : null;
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!inGame() || !(event.getPacket() instanceof ServerboundMovePlayerPacket)) {
            return;
        }
        if (held.size() >= limit.getInt()) {
            // Flush by restarting. Disabling releases everything held.
            setEnabled(false);
            setEnabled(true);
            return;
        }
        event.cancel();
        held.add(event.getPacket());
    }

    @Override
    protected void onDisable() {
        // Only replay onto the same connection they were captured from.
        if (mc.player != null && mc.player.connection == connection) {
            for (Packet<?> packet : held) {
                mc.player.connection.send(packet);
            }
        }
        held.clear();
        connection = null;
    }
}
