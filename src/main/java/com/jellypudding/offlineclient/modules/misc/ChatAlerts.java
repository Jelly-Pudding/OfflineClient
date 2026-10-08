package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.AlertSound;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ShownChat;
import com.jellypudding.offlineclient.util.TextLines;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.sounds.SoundEvents;

// Only lines other players write set it off. Your own lines and server notices never do.
// A plugin writes a whisper as server text that may name nobody the tab list knows.
public final class ChatAlerts extends Module {

    // One toast shows the latest alert. A new alert rewrites it in place.
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

    private final BoolSetting yourName = new BoolSetting("Your name",
        "Alerts you when another player writes your name.", true);
    private final TextLines words = new TextLines("Words",
        "How many other words set off an alert.",
        "A word that sets off an alert when another player writes it. Click to type it.").plain();
    private final BoolSetting whispers = new BoolSetting("Whispers",
        "Alerts you when another player whispers to you.", true);
    private final AlertSound sound = new AlertSound("",
        "Rings when chat mentions you or a word you pick or someone whispers to you.",
        SoundEvents.NOTE_BLOCK_PLING.value(), 1, true);
    private final BoolSetting toast = new BoolSetting("Toast",
        "Shows who wrote the line and what they said in a toast at the top right.", true);

    public ChatAlerts() {
        super("ChatAlerts", "Rings and shows a toast when chat mentions you or a word you pick or a player"
            + " whispers to you.", Category.MISC);
        addSettings(yourName);
        addSettings(words.settings());
        addSettings(whispers);
        addSettings(sound.settings());
        addSettings(toast);
        searchTags("mention", "highlight", "ping", "name alert", "whisper", "message");
    }

    @Override
    protected void onDisable() {
        sound.stop();
    }

    // Fired on the netty thread. The line is read on the game thread where the tab list lives.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        if (ChatSender.isChat(packet)) {
            boolean whisper = ShownChat.whisper(packet);
            mc.schedule(() -> onLine(packet, whisper));
        }
    }

    private void onLine(Packet<?> packet, boolean whisper) {
        if (!isEnabled() || mc.player == null) {
            return;
        }
        ChatSender.Line line = ChatSender.lineOf(packet);
        if (line != null && (line.isOwn() || Modules.mutes(line.name()))) {
            return;
        }
        boolean alert = whisper ? whispers.isOn() : line != null && mentions(line.text());
        if (!alert) {
            return;
        }
        String name = line == null ? "Whisper" : line.name();
        String text = line == null ? wholeText(packet) : line.text();
        // A SecretChat ping is a whisper of sealed code that no player typed.
        SecretChat secretChat = Modules.get(SecretChat.class);
        if (secretChat != null && secretChat.isPing(text)) {
            return;
        }
        sound.ring();
        Modules.alert(Webhook.Alert.MENTION, (line == null ? "" : name + (whisper ? " whispered " : " wrote "))
            + text);
        if (toast.isOn()) {
            // NameProtect covers the toast as well as the chat.
            NameProtect nameProtect = Modules.get(NameProtect.class);
            Component shownName = Component.literal(nameProtect == null ? name : nameProtect.display(name));
            Component shownText = Component.literal(text);
            SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST, shownName,
                nameProtect == null ? shownText : nameProtect.filter(shownText));
        }
    }

    // A whisper no writer could be read from is shown whole.
    private static String wholeText(Packet<?> packet) {
        return switch (packet) {
            case ClientboundSystemChatPacket system -> ChatSender.plain(system.content());
            case ClientboundPlayerChatPacket chat -> chat.body().content();
            default -> "";
        };
    }

    private boolean mentions(String text) {
        if (yourName.isOn() && ChatSender.holds(text, mc.player.getGameProfile().name())) {
            return true;
        }
        for (String word : words.all()) {
            if (ChatSender.holds(text, word)) {
                return true;
            }
        }
        return false;
    }
}
