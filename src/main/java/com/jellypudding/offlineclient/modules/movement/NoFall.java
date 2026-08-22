package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.PacketUtil;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Flips the on ground flag on every airborne movement packet. Server fall
 * tracking resets on every on ground packet.
 */
public final class NoFall extends Module {

    private final BoolSetting elytra = new BoolSetting("Elytra",
        "Also protect whilst gliding. Some servers may rubberband.", true);

    public NoFall() {
        super("NoFall", "Stops fall damage by telling the server you are on the ground.", Category.MOVEMENT);
        addSettings(elytra);
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!inGame() || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet)) {
            return;
        }
        // Creative mode has no fall damage.
        if (mc.player.getAbilities().invulnerable) {
            return;
        }
        if (mc.player.isFallFlying() && !elytra.isOn()) {
            return;
        }
        if (packet.isOnGround()) {
            return;
        }
        event.setPacket(PacketUtil.withOnGround(packet, mc.player, true));
    }
}
