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

    // What plugins write into a whisper they send as server text.
    private static final List<String> WHISPER_MARKS = List.of("whispers to you", "-> me]");

    private ShownChat() {
    }

    // The player who wrote a line on its way into chat. Null for a line the server or the
    // client wrote and for a whisper you sent. It reads the tab list and belongs on the game thread.
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
        // A line that came as server text is searched for its writer the way that packet would be.
        ChatSender.Line written = ChatSender.lineOf(new ClientboundSystemChatPacket(line, false));
        return written == null ? null : written.name();
    }

    // True for a line that whispers to you.
    public static boolean whisper(Component line) {
        TranslatableContents decoration = decoration(line);
        return (decoration != null && decoration.getKey().equals(INCOMING_WHISPER)) || marked(ChatSender.plain(line));
    }

    // The same for a packet as it arrives.
    public static boolean whisper(Packet<?> packet) {
        return switch (packet) {
            case ClientboundPlayerChatPacket chat -> chat.chatType().chatType().is(ChatType.MSG_COMMAND_INCOMING);
            case ClientboundSystemChatPacket system when !system.overlay() -> marked(ChatSender.plain(system.content()));
            default -> false;
        };
    }

    private static boolean marked(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return WHISPER_MARKS.stream().anyMatch(lower::contains);
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
