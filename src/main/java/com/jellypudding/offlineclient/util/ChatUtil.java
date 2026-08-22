package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.network.chat.Component;

public final class ChatUtil {

    /** §b[§3Offline§b]§r */
    private static final String PREFIX = "§b[§3Offline§b]§r ";

    private ChatUtil() {
    }

    public static void message(String message) {
        component(Component.literal(message));
    }

    public static void error(String message) {
        message("§c" + message);
    }

    public static void component(Component component) {
        if (OfflineClient.MC.player == null) {
            return;
        }
        OfflineClient.MC.gui.hud.getChat()
            .addClientSystemMessage(Component.literal(PREFIX).append(component));
    }
}
