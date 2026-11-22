package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.ChatSendEvent;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.SentLine;
import com.jellypudding.offlineclient.util.Shortcuts;
import com.jellypudding.offlineclient.util.ShownChat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

// What BetterChat does to a message you send.
final class ChatRewrite {

    private static final Minecraft MC = OfflineClient.MC;

    private static final String PLAIN = "abcdefghijklmnopqrstuvwxyz";
    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴩqʀꜱᴛᴜᴠᴡxyᴢ";

    // A number of three digits or more then one of two or more read as coordinates.
    private static final Pattern COORDINATES = Pattern.compile("-?\\d{3,}[\\s,;/]+-?\\d{2,}");

    private static final int SHORTCUTS = 32;

    private static final class Shortcut {

        final TextSetting typed;
        final TextSetting sent;

        Shortcut(int number, NumberSetting count) {
            int slot = number - 1;
            typed = new TextSetting("Shortcut " + number,
                "Text of yours that is swapped as you send it. It counts whatever its case and only where it"
                    + " stands apart from other letters. Click to type it.", "")
                .under(count, () -> slot < count.getInt());
            sent = new TextSetting("Becomes " + number,
                "What goes out in place of the shortcut. Click to type it.", "")
                .under(typed, () -> slot < count.getInt());
        }

        Shortcuts.Pair pair() {
            return typed.isBlank() ? null : new Shortcuts.Pair(typed.getValue().trim(), sent.getValue());
        }
    }

    private final BoolSetting guardCoordinates = new BoolSetting("Guard coordinates",
        "Holds back a message that looks like coordinates until you send it a second time.",
        true);

    private final NumberSetting shortcutCount = new NumberSetting("Shortcuts",
        "How many of your own words are swapped for other text as you send a message. A row opens for each.",
        0, 0, SHORTCUTS, 1).max(SHORTCUTS);
    private final Shortcut[] shortcuts = new Shortcut[SHORTCUTS];
    private final BoolSetting shortcutCommands = new BoolSetting("In commands",
        "Shortcuts are also swapped in the words after a command such as a whisper or a reply. The name of a"
            + " player online and a command that carries a password are never touched.", false)
        .under(shortcutCount, () -> shortcutCount.getInt() > 0);
    private final BoolSetting replyShortcut = new BoolSetting("Reply shortcut",
        "Turns the reply command and your words into a whisper to the player who whispered to you last. For"
            + " servers that have no reply command of their own.", false);
    private final TextSetting replyCommand = new TextSetting("Reply command",
        "The short command you type in front of a reply. Click to type it.", "/r")
        .under(replyShortcut);
    private final TextSetting whisperWith = SentLine.whisperWith()
        .under(replyShortcut);

    private final BoolSetting annoy = new BoolSetting("Annoy",
        "Sends your messages in aLtErNaTiNg case.", false);
    private final BoolSetting fancy = new BoolSetting("Fancy chat",
        "Sends your messages in small caps.", false);

    private final BoolSetting prefix = new BoolSetting("Prefix",
        "Puts something in front of every message you send.", false);
    private final BoolSetting prefixRandom = new BoolSetting("Random prefix",
        "Uses a random three digit number instead of the text.", false)
        .under(prefix);
    private final TextSetting prefixText = new TextSetting("Prefix text",
        "The text put in front. Click to type it.", "> ")
        .under(prefix, () -> prefix.isOn() && !prefixRandom.isOn());
    private final BoolSetting prefixSmall = new BoolSetting("Small caps prefix",
        "Writes the prefix in small caps.", false)
        .under(prefix, () -> prefix.isOn() && !prefixRandom.isOn());

    private final BoolSetting suffix = new BoolSetting("Suffix",
        "Puts something on the end of every message you send.", false);
    private final BoolSetting suffixRandom = new BoolSetting("Random suffix",
        "Uses a random three digit number instead of the text.", false)
        .under(suffix);
    private final TextSetting suffixText = new TextSetting("Suffix text",
        "The text put on the end. Click to type it.", " | offline")
        .under(suffix, () -> suffix.isOn() && !suffixRandom.isOn());
    private final BoolSetting suffixSmall = new BoolSetting("Small caps suffix",
        "Writes the suffix in small caps.", true)
        .under(suffix, () -> suffix.isOn() && !suffixRandom.isOn());

    private final BooleanSupplier enabled;
    private String heldBack;
    private String lastWhisperer;

    ChatRewrite(BooleanSupplier enabled) {
        this.enabled = enabled;
        for (int i = 0; i < SHORTCUTS; i++) {
            shortcuts[i] = new Shortcut(i + 1, shortcutCount);
        }
    }

    BoolSetting guardRow() {
        return guardCoordinates;
    }

    Setting<?>[] rewriteRows() {
        List<Setting<?>> rows = new ArrayList<>();
        rows.add(shortcutCount);
        for (Shortcut shortcut : shortcuts) {
            rows.add(shortcut.typed);
            rows.add(shortcut.sent);
        }
        rows.addAll(List.of(shortcutCommands, replyShortcut, replyCommand, whisperWith, annoy, fancy,
            prefix, prefixRandom, prefixText, prefixSmall, suffix, suffixRandom, suffixText, suffixSmall));
        return rows.toArray(Setting<?>[]::new);
    }

    // A sign the server kicks for never goes out and chat stays as short as the server takes it.
    String rewrite(String message) {
        if (!enabled.getAsBoolean()) {
            return message;
        }
        if (message.startsWith("/")) {
            return StringUtil.filterText(rewriteCommand(message));
        }
        String body = Shortcuts.swap(message, shortcutPairs());
        if (annoy.isOn()) {
            body = alternate(body);
        }
        if (fancy.isOn()) {
            body = smallCaps(body);
        }
        return StringUtil.trimChatMessage(StringUtil.filterText(affix(prefix, prefixRandom, prefixText, prefixSmall,
            "(%03d) ") + body + affix(suffix, suffixRandom, suffixText, suffixSmall, " (%03d)")));
    }

    private String rewriteCommand(String typed) {
        String command = shortcutCommands.isOn()
            ? Shortcuts.swapInCommand(typed, shortcutPairs(), ChatRewrite::isOnline) : typed;
        String reply = replyWords(command);
        String whisper = reply == null || lastWhisperer == null ? ""
            : SentLine.whisper(whisperWith.getValue(), lastWhisperer, reply);
        return whisper.isEmpty() ? command : whisper;
    }

    // A command that carries a password is never a reply whatever it starts with.
    private String replyWords(String command) {
        if (!replyShortcut.isOn() || replyCommand.isBlank() || Shortcuts.carriesPassword(command)) {
            return null;
        }
        String typed = SentLine.asCommand(replyCommand.getValue());
        int space = command.indexOf(' ');
        String word = space == -1 ? command : command.substring(0, space);
        return word.equalsIgnoreCase(typed) ? command.substring(word.length()).strip() : null;
    }

    private static boolean isOnline(String name) {
        return MC.getConnection() != null && MC.getConnection().getPlayerInfoIgnoreCase(name) != null;
    }

    private List<Shortcuts.Pair> shortcutPairs() {
        List<Shortcuts.Pair> pairs = new ArrayList<>();
        for (int i = 0; i < shortcutCount.getInt(); i++) {
            Shortcuts.Pair pair = shortcuts[i].pair();
            if (pair != null) {
                pairs.add(pair);
            }
        }
        return pairs;
    }

    // Fired on the netty thread. The whisper is read on the game thread where the tab list lives.
    void onPacketReceive(Packet<?> packet) {
        if (packet instanceof ClientboundLoginPacket) {
            // A name from one server may belong to somebody else on the next.
            MC.schedule(() -> lastWhisperer = null);
        } else if (replyShortcut.isOn() && ChatSender.isChat(packet)) {
            MC.schedule(() -> noteWhisper(packet));
        }
    }

    // Only a whisper whose sender cannot be made up is remembered. A reply must never go to
    // a name some other player typed into a line.
    private void noteWhisper(Packet<?> packet) {
        ShownChat.Whisper whisper = MC.player == null ? null : ShownChat.whisperOf(packet);
        if (whisper != null && SentLine.oneWord(whisper.from())
            && !whisper.from().equalsIgnoreCase(MC.player.getGameProfile().name()) && !Modules.mutes(whisper.from())) {
            lastWhisperer = whisper.from();
        }
    }

    // The random form carries its own gap on the side that meets the message.
    private static String affix(BoolSetting on, BoolSetting random, TextSetting text,
                                BoolSetting small, String randomForm) {
        if (!on.isOn()) {
            return "";
        }
        if (random.isOn()) {
            return String.format(Locale.ROOT, randomForm, ThreadLocalRandom.current().nextInt(1000));
        }
        return small.isOn() ? smallCaps(text.getValue()) : text.getValue();
    }

    private static String alternate(String message) {
        StringBuilder out = new StringBuilder(message.length());
        boolean upper = true;
        for (int point : message.codePoints().toArray()) {
            out.appendCodePoint(upper ? Character.toUpperCase(point) : Character.toLowerCase(point));
            upper = !upper;
        }
        return out.toString();
    }

    private static String smallCaps(String message) {
        StringBuilder out = new StringBuilder(message.length());
        for (char letter : message.toCharArray()) {
            int at = PLAIN.indexOf(Character.toLowerCase(letter));
            out.append(at == -1 ? letter : SMALL.charAt(at));
        }
        return out.toString();
    }

    void onChatSend(ChatSendEvent event) {
        String message = event.getMessage();
        String reply = message.startsWith("/") ? replyWords(message) : null;
        if (reply != null && (reply.isEmpty() || lastWhisperer == null)) {
            event.cancel();
            ChatUtil.message(reply.isEmpty() ? "§7Type what you want to say after the reply command."
                : "§7Nobody has whispered to you yet.");
            return;
        }
        if (!guardCoordinates.isOn()) {
            return;
        }
        if (message.startsWith("/") || !COORDINATES.matcher(message).find()) {
            return;
        }
        // Sending the same text again confirms it and lets it through.
        if (message.equals(heldBack)) {
            heldBack = null;
            return;
        }
        heldBack = message;
        event.cancel();
        ChatUtil.message("That looks like coordinates. Send it again if you mean it.");
    }

    void forget() {
        heldBack = null;
        lastWhisperer = null;
    }
}
