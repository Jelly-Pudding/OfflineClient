package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import net.minecraft.network.chat.Component;

public final class ChatUtil {

    private static final String PREFIX = "§b[§3Offline§b]§r ";

    private ChatUtil() {
    }

    public static void message(String message) {
        component(Component.literal(message));
    }

    public static void error(String message) {
        message("§c" + message);
    }

    // Announces the new state of a module and marks the config for saving.
    public static void toggled(Module module) {
        message("§b" + module.getName() + " §7is now "
            + (module.isEnabled() ? "§aenabled" : "§cdisabled") + "§7.");
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }

    public static void component(Component component) {
        if (OfflineClient.MC.player == null) {
            return;
        }
        OfflineClient.MC.gui.hud.getChat()
            .addClientSystemMessage(Component.literal(PREFIX).append(component));
    }
}
