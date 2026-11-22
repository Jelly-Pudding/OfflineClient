package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;

// Who sees the arm swing of something a module does. The server swings the arm for everyone
// else by itself once a block is placed or a thing is used and only this screen is left to
// decide. A hit and a tick of mining reach the others through the punch packet alone.
public enum SwingMode {
    BOTH, PACKET, CLIENT, NONE;

    public static SwingMode of(boolean onScreen) {
        return onScreen ? BOTH : PACKET;
    }

    public void use(InteractionHand hand) {
        if (onScreen()) {
            swingUse(hand);
        }
    }

    public void use() {
        use(InteractionHand.MAIN_HAND);
    }

    // After a hit. The server has started the attack cooldown again for the hit already and
    // the punch starts it once more.
    public void hit() {
        if (onScreen()) {
            swingHit();
        }
        LocalPlayer player = OfflineClient.MC.player;
        if (toOthers() && player != null) {
            player.connection.send(ServerboundPunchPacket.INSTANCE);
            player.resetOnlyAttackStrengthTicker();
        }
    }

    // On a tick of mining. The plain game punches on every one of them and each punch starts
    // the attack cooldown again. The hit timer keeps the punch back whilst a hit charges.
    public void mine() {
        if (onScreen()) {
            swingHit();
        }
        LocalPlayer player = OfflineClient.MC.player;
        if (toOthers() && player != null && AttackTimer.letsMiningPunch()) {
            player.connection.send(ServerboundPunchPacket.INSTANCE);
        }
    }

    private boolean onScreen() {
        return this == BOTH || this == CLIENT;
    }

    private boolean toOthers() {
        return this == BOTH || this == PACKET;
    }

    public static void swingUse(InteractionHand hand) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null) {
            player.swing(hand, player.getItemInHand(hand).getInteractAnimation(), false);
        }
    }

    public static void swingHit() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null) {
            player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), false);
        }
    }

    public static EnumSetting<SwingMode> setting(SwingMode defaultValue) {
        return described(new EnumSetting<>("Swing", "Who sees your arm swing.", defaultValue));
    }

    public static EnumSetting<SwingMode> settingWithUses(SwingMode defaultValue) {
        return described(new EnumSetting<>("Swing",
            "Who sees your arm swing. The server shows everyone else the swing of a block you place whatever this says.",
            defaultValue));
    }

    private static EnumSetting<SwingMode> described(EnumSetting<SwingMode> row) {
        return row.describe(BOTH, "You and everyone else.")
            .describe(PACKET, "Everyone else but not you.")
            .describe(CLIENT, "Only you.")
            .describe(NONE, "Nobody.");
    }

    public static BoolSetting useSetting(boolean onScreen) {
        return new BoolSetting("Swing", "Swings your arm on your own screen. Everyone else sees it either way.",
            onScreen);
    }
}
