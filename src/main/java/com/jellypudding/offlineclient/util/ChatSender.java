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

    // What a server shows in front of a nickname a player picked for themselves. Anyone can pick
    // the name of somebody else.
    private static final char NICKNAME = '~';

    private ChatSender() {
    }

    // True for the packets a chat line comes in. The action bar is left out.
    public static boolean isChat(Packet<?> packet) {
        return packet instanceof ClientboundPlayerChatPacket
            || packet instanceof ClientboundSystemChatPacket system && !system.overlay();
    }

    // Null for a line the server wrote itself. It reads the tab list and belongs on the game thread.
    public static Line lineOf(Packet<?> packet) {
        return switch (packet) {
            case ClientboundPlayerChatPacket chat -> {
                ClientPacketListener connection = OfflineClient.MC.getConnection();
                PlayerInfo info = connection == null ? null : connection.getPlayerInfo(chat.sender());
                yield info == null ? null
                    : new Line(chat.sender(), info.getProfile().name(), chat.body().content());
            }
            case ClientboundSystemChatPacket system when !system.overlay() -> writerOf(system.content());
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

    // The writer of a line only where no player can have made the line up. Player chat names
    // its sender. In server text the writer has to be the one word before the first chat mark.
    // A player who types a name and a mark into their own line has their own name before it.
    // Null for anything else such as a name with a rank in front. It reads the tab list and
    // belongs on the game thread.
    public static String certainWriter(Packet<?> packet) {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (connection == null) {
            return null;
        }
        switch (packet) {
            case ClientboundPlayerChatPacket chat -> {
                PlayerInfo info = connection.getPlayerInfo(chat.sender());
                return info == null ? null : info.getProfile().name();
            }
            case ClientboundSystemChatPacket system when !system.overlay() -> {
                String name = soleWriter(plain(system.content()));
                return name != null && connection.getPlayerInfo(name) != null ? name : null;
            }
            default -> {
                return null;
            }
        }
    }

    // The one word that stands before the first chat mark of a line. Null when there is no
    // mark or more than one word comes before it.
    public static String soleWriter(String text) {
        int mark = firstMark(text);
        return mark == -1 ? null : soleName(text.substring(0, mark));
    }

    // The name a line shows in front of it in the shape of chat whether or not anybody online
    // goes by it. That is a name in angle brackets such as <Steve> or one word before the first
    // chat mark such as Steve or ~Steve with any tags in square brackets in front of either.
    // Null for a line that opens any other way.
    public static String apparentWriter(String text) {
        String line = afterTags(text);
        if (line.startsWith("<")) {
            int close = line.indexOf('>');
            String inside = close == -1 ? "" : line.substring(1, close).strip();
            return inside.isEmpty() ? null : inside;
        }
        int mark = firstMark(line);
        String before = mark == -1 ? "" : line.substring(0, mark).strip();
        return before.isEmpty() || before.chars().anyMatch(Character::isWhitespace) ? null : before;
    }

    // The name a player on the tab list goes by whatever case it was written in. Null when
    // nobody listed goes by it. It reads the tab list and belongs on the game thread.
    public static String listedName(String name) {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfoIgnoreCase(name);
        return info != null && connection.getListedOnlinePlayers().contains(info) ? info.getProfile().name() : null;
    }

    // The text with any tags in square brackets at its front taken off.
    private static String afterTags(String text) {
        String rest = text.stripLeading();
        int close;
        while (rest.startsWith("[") && (close = rest.indexOf(']')) != -1) {
            rest = rest.substring(close + 1).stripLeading();
        }
        return rest;
    }

    // The one name a stretch of text holds with nothing but signs and spaces round it. Null
    // when a letter or a digit stands anywhere else or a tilde marks the name as a nickname.
    private static String soleName(String text) {
        int from = 0;
        int to = text.length();
        while (from < to && !ChatUtil.isNameChar(text.charAt(from))) {
            from++;
        }
        while (to > from && !ChatUtil.isNameChar(text.charAt(to - 1))) {
            to--;
        }
        String name = text.substring(from, to);
        boolean nickname = from > 0 && text.charAt(from - 1) == NICKNAME;
        return !nickname && !name.isEmpty() && name.chars().allMatch(c -> ChatUtil.isNameChar((char) c)) ? name : null;
    }

    // True when the name of anyone online stands whole in the text. It reads the tab list and
    // belongs on the game thread.
    public static boolean namesAnyone(String text) {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (connection == null) {
            return false;
        }
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            String name = info.getProfile().name();
            if (!name.isEmpty() && ChatUtil.wholeWordIndex(text, name, 0) != -1) {
                return true;
            }
        }
        return false;
    }

    private static int firstMark(String text) {
        for (int at = 0; at < text.length(); at++) {
            if (MARKS.indexOf(text.charAt(at)) != -1) {
                return at;
            }
        }
        return -1;
    }

    // The writer of a line of server text. That is the earliest online name with a chat mark
    // after it. Null off a server and when no name has one. It reads the tab list and belongs
    // on the game thread.
    static Line writerOf(Component line) {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (connection == null) {
            return null;
        }
        String text = plain(line);
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
