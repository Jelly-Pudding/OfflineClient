package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import java.util.List;
import java.util.Locale;
import java.util.Set;

// Reads chat for who wrote a line and whether it whispers to you. The game wraps player chat
// in a decoration with the writer among its arguments. A plugin writes its chat and its
// whispers as plain server text.
public final class ShownChat {

    private static final String INCOMING_WHISPER = "commands.message.display.incoming";
    // A whisper you send comes back to you named after the player it went to.
    private static final String OUTGOING_WHISPER = "commands.message.display.outgoing";
    // Team chat names the team first and the writer second.
    private static final String TEAM = "chat.type.team.text";

    private static final Set<String> DECORATIONS = Set.of("chat.type.text", "chat.type.emote",
        "chat.type.announcement", TEAM, INCOMING_WHISPER, OUTGOING_WHISPER);

    // What a whisper sent as server text puts between the sender and the message. The first is
    // the wording of the game and plugins put the second in brackets with the sender.
    private static final String VANILLA_MARK = " whispers to you";
    private static final String PLUGIN_MARK = " -> me]";
    private static final List<String> WHISPER_MARKS = List.of(VANILLA_MARK.strip(), PLUGIN_MARK.strip());

    public record Whisper(String from, String text) {
    }

    private ShownChat() {
    }

    // Null for a line the server or the client wrote and for a whisper you sent. Game thread only.
    public static String writer(Component line, GuiMessageSource source) {
        if (source == GuiMessageSource.SYSTEM_CLIENT) {
            return null;
        }
        TranslatableContents decoration = decoration(line);
        if (decoration != null) {
            if (decoration.getKey().equals(OUTGOING_WHISPER)) {
                return null;
            }
            int senderAt = decoration.getKey().equals(TEAM) ? 1 : 0;
            Object[] args = decoration.getArgs();
            if (args.length > senderAt) {
                Object sender = args[senderAt];
                String shown = sender instanceof Component name ? ChatSender.plain(name) : String.valueOf(sender);
                return onlineName(shown);
            }
        }
        ChatSender.Line written = ChatSender.writerOf(line);
        return written == null ? null : written.name();
    }

    public static boolean whisper(Component line) {
        TranslatableContents decoration = decoration(line);
        return (decoration != null && decoration.getKey().equals(INCOMING_WHISPER)) || marked(ChatSender.plain(line));
    }

    public static boolean whisper(Packet<?> packet) {
        return switch (packet) {
            case ClientboundPlayerChatPacket chat -> chat.chatType().chatType().is(ChatType.MSG_COMMAND_INCOMING);
            case ClientboundSystemChatPacket system when !system.overlay() -> marked(ChatSender.plain(system.content()));
            default -> false;
        };
    }

    // The whisper a packet brings with a sender no player can have made up. Player chat names
    // its sender and its kind. Server text only counts in one of the shapes whisperIn reads.
    // Null for anything else. It reads the tab list and belongs on the game thread.
    public static Whisper whisperOf(Packet<?> packet) {
        if (packet instanceof ClientboundPlayerChatPacket) {
            ChatSender.Line line = whisper(packet) ? ChatSender.lineOf(packet) : null;
            return line == null ? null : new Whisper(line.name(), line.text().trim());
        }
        if (!(packet instanceof ClientboundSystemChatPacket system) || system.overlay()
            || ChatSender.lineOf(packet) != null) {
            return null;
        }
        return whisperIn(ChatSender.plain(system.content()));
    }

    // The whisper a line of server text holds. Null unless the line opens in one of the two
    // shapes a whisper comes in such as Bob whispers to you or [Bob -> me] with nothing at all in
    // front. A player who types either shape into a line of their own has their name or a sign
    // such as the star of an emote in front of it.
    public static Whisper whisperIn(String text) {
        Whisper whisper = shaped(text, "", VANILLA_MARK);
        return whisper != null ? whisper : shaped(text, "[", PLUGIN_MARK);
    }

    private static Whisper shaped(String text, String opening, String mark) {
        if (!text.startsWith(opening)) {
            return null;
        }
        int from = opening.length();
        int to = from;
        while (to < text.length() && ChatUtil.isNameChar(text.charAt(to))) {
            to++;
        }
        if (to == from || !text.regionMatches(true, to, mark, 0, mark.length())) {
            return null;
        }
        return new Whisper(text.substring(from, to), afterMark(text.substring(to + mark.length())));
    }

    // Null for player chat and for server text that reads as the line of a player or as a whisper.
    // Game thread only.
    public static String serverText(Packet<?> packet) {
        if (!(packet instanceof ClientboundSystemChatPacket system) || system.overlay()
            || ChatSender.lineOf(packet) != null) {
            return null;
        }
        String text = ChatSender.plain(system.content());
        return marked(text) ? null : text;
    }

    private static boolean marked(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return WHISPER_MARKS.stream().anyMatch(lower::contains);
    }

    private static String afterMark(String text) {
        int from = 0;
        while (from < text.length() && (text.charAt(from) == ' ' || text.charAt(from) == ':')) {
            from++;
        }
        return text.substring(from).trim();
    }

    // The first decoration anywhere in the line. Other modules may have wrapped it by now.
    private static TranslatableContents decoration(Component line) {
        if (line.getContents() instanceof TranslatableContents translatable
            && DECORATIONS.contains(translatable.getKey())) {
            return translatable;
        }
        for (Component part : line.getSiblings()) {
            TranslatableContents found = decoration(part);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // A server can dress a name up with a rank. The online player named inside it is the writer.
    private static String onlineName(String shown) {
        String text = shown.trim();
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (connection == null || connection.getPlayerInfo(text) != null) {
            return text;
        }
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            if (ChatUtil.wholeWordIndex(text, info.getProfile().name(), 0) != -1) {
                return info.getProfile().name();
            }
        }
        return text;
    }
}
