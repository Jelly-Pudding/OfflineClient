package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.PacketUtil;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

// The server charges no walking exhaustion to airborne players and charges
// a sprint jump four times what a walking jump costs.
public final class AntiHunger extends Module {

    private final BoolSetting sprint = new BoolSetting("Sprint",
        "Never tells the server you are sprinting. A sprint jump then costs the same as a walking jump.",
        true);
    private final BoolSetting onGround = new BoolSetting("On ground",
        "Tells the server you are in the air whilst you walk so walking costs no hunger.", true);

    public AntiHunger() {
        super("AntiHunger", "Slows down how fast you get hungry.", Category.PLAYER);
        addSettings(sprint, onGround);
        searchTags("hunger", "food", "exhaustion");
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        // The packet thread can drop the player and the game mode mid handler.
        LocalPlayer player = mc.player;
        MultiPlayerGameMode gameMode = mc.gameMode;
        if (player == null || gameMode == null) {
            return;
        }
        if (event.getPacket() instanceof ServerboundPlayerCommandPacket command) {
            if (sprint.isOn() && command.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING) {
                event.cancel();
            }
            return;
        }
        if (!onGround.isOn() || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet)
            || !packet.isOnGround()) {
            return;
        }
        if (player.fallDistance > 0.5) {
            return;
        }
        // A glide only ends when the server hears about the landing.
        if (player.isFallFlying()) {
            return;
        }
        // The server slows mining a lot for airborne players.
        if (gameMode.isDestroying()) {
            return;
        }
        event.setPacket(PacketUtil.withOnGround(packet, player, false));
    }
}
