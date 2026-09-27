package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import java.util.Locale;
import java.util.UUID;

// Works out which player wrote a chat line. Player chat names its sender. Some servers
// turn every line into plain server text and then the writer is an online player whose
// name sits right before a chat mark.
public final class ChatSender {

    // What one player wrote with their name taken off the front.
    public record Line(UUID sender, String name, String text) {

        public boolean isOwn() {
            LocalPlayer player = OfflineClient.MC.player;
            return player != null && player.getUUID().equals(sender);
        }
    }

    // What servers put between a name and the message. The close of <Bob> or a colon or a double arrow.
    private static final String MARKS = ">:»";

    private ChatSender() {
    }

    // True for the packets a chat line comes in. The action bar is left out.
    public static boolean isChat(Packet<?> packet) {
        return packet instanceof ClientboundPlayerChatPacket
            || packet instanceof ClientboundSystemChatPacket system && !system.overlay();
    }

    // Null for a line the server wrote itself. It reads the tab list and belongs on the game thread.
    public static Line lineOf(Packet<?> packet) {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (connection == null) {
            return null;
        }
        return switch (packet) {
            case ClientboundPlayerChatPacket chat -> {
                PlayerInfo info = connection.getPlayerInfo(chat.sender());
                yield info == null ? null
                    : new Line(chat.sender(), info.getProfile().name(), chat.body().content());
            }
            case ClientboundSystemChatPacket system when !system.overlay() ->
                writerOf(connection, plain(system.content()));
            default -> null;
        };
    }

    // The text of a line with any colour codes taken out.
    public static String plain(Component text) {
        return ChatFormatting.stripFormatting(text.getString());
    }

    // True when the words stand whole in the text whatever their case.
    public static boolean holds(String text, String words) {
        return ChatUtil.wholeWordIndex(text.toLowerCase(Locale.ROOT), words.toLowerCase(Locale.ROOT), 0) != -1;
    }

    // The earliest online name with a chat mark after it. Null when no name has one.
    private static Line writerOf(ClientPacketListener connection, String text) {
        PlayerInfo writer = null;
        int nameAt = Integer.MAX_VALUE;
        int markAt = -1;
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            String name = info.getProfile().name();
            int at = ChatUtil.wholeWordIndex(text, name, 0);
            while (at != -1 && at < nameAt) {
                int mark = markAfter(text, at + name.length());
                if (mark != -1) {
                    writer = info;
                    nameAt = at;
                    markAt = mark;
                    break;
                }
                at = ChatUtil.wholeWordIndex(text, name, at + 1);
            }
        }
        if (writer == null) {
            return null;
        }
        return new Line(writer.getProfile().id(), writer.getProfile().name(),
            text.substring(markAt + 1).trim());
    }

    // Where the mark sits once spaces are skipped. Minus one when anything else comes first.
    private static int markAfter(String text, int from) {
        int at = from;
        while (at < text.length() && text.charAt(at) == ' ') {
            at++;
        }
        return at < text.length() && MARKS.indexOf(text.charAt(at)) != -1 ? at : -1;
    }
}
