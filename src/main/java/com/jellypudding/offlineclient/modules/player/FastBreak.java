package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.PacketUtil;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

public final class FastBreak extends Module {

    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between breaking blocks. Vanilla waits 5.", 0, 0, 5, 1, " ticks");
    private final BoolSetting airPenalty = new BoolSetting("No air penalty",
        "Mine at full speed in the air. Vanilla mines five times slower there.", true);

    public FastBreak() {
        super("FastBreak", "Removes the delay between breaking blocks.", Category.PLAYER);
        addSettings(delay, airPenalty);
    }

    public boolean removesAirPenalty() {
        return isEnabled() && airPenalty.isOn();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.gameMode == null) {
            return;
        }
        mc.gameMode.destroyDelay = Math.min(mc.gameMode.destroyDelay, delay.getInt());
    }

    // The server applies its own airborne mining penalty.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!airPenalty.isOn()
            || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet)
            || packet.isOnGround()) {
            return;
        }
        // The packet thread can drop the player and the game mode mid handler.
        LocalPlayer player = mc.player;
        MultiPlayerGameMode gameMode = mc.gameMode;
        if (player == null || gameMode == null || !gameMode.isDestroying()) {
            return;
        }
        event.setPacket(PacketUtil.withOnGround(packet, player, true));
    }
}
