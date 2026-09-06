package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.EnumSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;

// Whether an action shows an arm swing on this screen or to the server or both.
public enum SwingMode {
    BOTH, PACKET, CLIENT, NONE;

    public void swing(InteractionHand hand) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        switch (this) {
            case BOTH -> player.swing(hand);
            case PACKET -> player.connection.send(new ServerboundSwingPacket(hand));
            case CLIENT -> player.swing(hand, false);
            case NONE -> {
            }
        }
    }

    public void swing() {
        swing(InteractionHand.MAIN_HAND);
    }

    public static EnumSetting<SwingMode> setting(SwingMode defaultValue) {
        return new EnumSetting<>("Swing", "Who sees your arm swing.", defaultValue)
            .describe(BOTH, "You and everyone else.")
            .describe(PACKET, "Everyone else but not you.")
            .describe(CLIENT, "Only you.")
            .describe(NONE, "Nobody.");
    }
}
