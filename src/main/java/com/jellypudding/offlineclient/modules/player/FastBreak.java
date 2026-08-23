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
        "Mine at full speed in the air. Vanilla mines five times slower off the ground.", true);
    private final NumberSetting speed = new NumberSetting("Speed",
        "Speeds up the break the client predicts. The server keeps its own timer.",
        1, 1, 10, 0.1, "x").min(1).max(100);

    public FastBreak() {
        super("FastBreak", "Breaks blocks faster and without the vanilla wait.", Category.PLAYER);
        addSettings(delay, speed, airPenalty);
        searchTags("fast break", "speed mine", "instant mine", "nuker speed", "haste");
    }

    @Override
    public String getSuffix() {
        return speed.getValue() > 1 ? speed.getValueString() : null;
    }

    /**
     * Multiplies the break progress the client predicts. The server runs its own
     * timer and a block can stay visible until that timer agrees.
     */
    public float speedMultiplier() {
        return isEnabled() ? speed.getFloat() : 1;
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
