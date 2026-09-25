package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Predicate;

public final class ChatUtil {

    private static final String TAG = "§b[§3Offline§b]";
    private static final String PREFIX = TAG + "§r ";

    private ChatUtil() {
    }

    public static void message(String message) {
        component(Component.literal(message));
    }

    public static void error(String message) {
        message("§c" + message);
    }

    // Leaves the server. The disconnect screen shows the message under the client tag.
    public static void leaveServer(String message) {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (connection != null) {
            connection.getConnection().disconnect(Component.literal(TAG + " §f" + message));
        }
    }

    // Takes the newest line the test accepts out of the last few in chat and hands back
    // its text. Null when none of them matched.
    public static String removeRecent(ChatComponent chat, int depth, Predicate<String> text) {
        List<GuiMessage> all = chat.allMessages;
        for (int i = 0; i < Math.min(depth, all.size()); i++) {
            String line = all.get(i).content().getString();
            if (text.test(line)) {
                all.remove(i);
                chat.refreshTrimmedMessages();
                return line;
            }
        }
        return null;
    }

    // Sends a line as the player. A line starting with a slash runs as a server
    // command the way the chat screen runs it. Chat would show it as text.
    public static void say(String text) {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (connection == null || text.isBlank()) {
            return;
        }
        if (text.startsWith("/")) {
            connection.sendCommand(text.substring(1));
        } else {
            connection.sendChat(text);
        }
    }

    public static void toggled(Module module) {
        message("§b" + module.getName() + " §7is now "
            + (module.isEnabled() ? "§aenabled" : "§cdisabled") + "§7.");
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
