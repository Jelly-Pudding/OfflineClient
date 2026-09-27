package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.TextLines;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.sounds.SoundEvents;

// Only lines other players write set it off. Your own lines and server notices never do.
public final class ChatAlerts extends Module {

    // One toast shows the latest alert. A new alert rewrites it in place.
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

    private final BoolSetting yourName = new BoolSetting("Your name",
        "Alerts you when another player writes your name.", true);
    private final TextLines words = new TextLines("Words",
        "How many other words set off an alert.",
        "A word that sets off an alert when another player writes it. Click to type it.").plain();
    private final BoolSetting sound = new BoolSetting("Sound",
        "Plays a ping with every alert.", true);
    private final BoolSetting toast = new BoolSetting("Toast",
        "Shows who wrote the line and what they said in a toast at the top right.", true);

    public ChatAlerts() {
        super("ChatAlerts", "Plays a sound and shows a toast when chat mentions you or a word you pick.",
            Category.MISC);
        addSettings(yourName);
        addSettings(words.settings());
        addSettings(sound, toast);
        searchTags("mention", "highlight", "ping", "name alert");
    }

    // Fired on the netty thread. The line is read on the game thread where the tab list lives.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        if (ChatSender.isChat(packet)) {
            mc.schedule(() -> onLine(ChatSender.lineOf(packet)));
        }
    }

    private void onLine(ChatSender.Line line) {
        if (line == null || line.isOwn() || !isEnabled() || mc.player == null || !mentions(line.text())) {
            return;
        }
        if (sound.isOn()) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1f));
        }
        if (toast.isOn()) {
            // NameProtect covers the toast as well as the chat.
            NameProtect nameProtect = Modules.get(NameProtect.class);
            Component name = Component.literal(nameProtect == null ? line.name() : nameProtect.display(line.name()));
            Component text = Component.literal(line.text());
            SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST, name,
                nameProtect == null ? text : nameProtect.filter(text));
        }
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
