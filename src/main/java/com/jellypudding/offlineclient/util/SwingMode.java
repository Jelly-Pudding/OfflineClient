package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.EnumSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
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
            case BOTH, CLIENT -> swingArm(hand);
            // The punch packet carries no hand. The server swings the main one and restarts its attack cooldown.
            // The client's cooldown restarts with it.
            case PACKET -> {
                player.connection.send(ServerboundPunchPacket.INSTANCE);
                player.resetOnlyAttackStrengthTicker();
            }
            case NONE -> {
            }
        }
    }

    public void swing() {
        swing(InteractionHand.MAIN_HAND);
    }

    // A swing only this screen sees.
    public static void swingArm(InteractionHand hand) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null) {
            player.swing(hand, player.getItemInHand(hand).getAttackAnimation(), false);
        }
    }

    public static EnumSetting<SwingMode> setting(SwingMode defaultValue) {
        return new EnumSetting<>("Swing", "Who sees your arm swing.", defaultValue)
            .describe(BOTH, "You and everyone else.")
            .describe(PACKET, "Everyone else but not you. Always the main hand. Restarts your attack cooldown.")
            .describe(CLIENT, "Only you.")
            .describe(NONE, "Nobody.");
    }
}
