package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

// Tells the server the sprint stopped for one attack and starts it again
// afterwards. The client keeps sprinting the whole time.
public final class SprintPause {

    private boolean pending;

    // Sends the stop. Nothing happens whilst one is already pending or the
    // player is not sprinting.
    public void pause() {
        LocalPlayer player = OfflineClient.MC.player;
        if (pending || player == null || !player.isSprinting()) {
            return;
        }
        send(player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING);
        pending = true;
    }

    // Sends the start again if a stop went out.
    public void resume() {
        if (!pending) {
            return;
        }
        pending = false;
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null && player.isSprinting()) {
            send(player, ServerboundPlayerCommandPacket.Action.START_SPRINTING);
        }
    }

    private static void send(LocalPlayer player, ServerboundPlayerCommandPacket.Action action) {
        player.connection.send(new ServerboundPlayerCommandPacket(player, action));
    }
}
