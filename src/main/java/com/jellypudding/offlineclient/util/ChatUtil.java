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

    public static void toggled(Module module) {
        message("§b" + module.getName() + " §7is now "
            + (module.isEnabled() ? "§aenabled" : "§cdisabled") + "§7.");
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }

    // Where the name next sits as a word of its own or minus one. Sam inside
    // Samuel is not Sam.
    public static int wholeWordIndex(String text, String name, int from) {
        int at = text.indexOf(name, from);
        while (at != -1) {
            boolean startClear = at == 0 || !isNameChar(text.charAt(at - 1));
            int end = at + name.length();
            boolean endClear = end >= text.length() || !isNameChar(text.charAt(end));
            if (startClear && endClear) {
                return at;
            }
            at = text.indexOf(name, at + 1);
        }
        return -1;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    public static void component(Component component) {
        if (OfflineClient.MC.player == null) {
            return;
        }
        OfflineClient.MC.gui.hud.getChat()
            .addClientSystemMessage(Component.literal(PREFIX).append(component));
    }
}
