package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.EntityAddedEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Locale;

// Sends a private message to every player the moment they come into view.
public final class MessageAura extends Module {

    // Names remembered. One player is only messaged once a session.
    private static final int MAX_TRACKED = 512;

    private final TextSetting message = new TextSetting("Message",
        "What every player is told. Click to type it.", "Offline on top");
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Leaves people on your friend list alone.", false);
    private final BoolSetting once = new BoolSetting("Once each",
        "Messages a player only the first time you see them.", true);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between one message and the next.", 20, 0, 200, 1, " ticks").min(0).max(1200);

    private final Deque<String> queue = new ArrayDeque<>();
    private final LinkedHashSet<String> told = new LinkedHashSet<>();
    private int timer;

    public MessageAura() {
        super("MessageAura", "Sends a private message to every player who comes into view.",
            Category.MISC);
        addSettings(message, ignoreFriends, once, delay);
        searchTags("msg", "whisper", "advert");
    }

    @Override
    public String getSuffix() {
        return count(queue.size());
    }

    @Override
    protected void onEnable() {
        queue.clear();
        told.clear();
        timer = 0;
    }

    @Override
    protected void onDisable() {
        queue.clear();
        told.clear();
    }

    @Subscribe
    private void onEntityAdded(EntityAddedEvent event) {
        if (!(event.getEntity() instanceof Player player) || player == mc.player) {
            return;
        }
        String name = player.getGameProfile().name();
        if (name.isBlank() || (ignoreFriends.isOn()
            && OfflineClient.INSTANCE.getFriendManager().isFriend(name))) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (once.isOn() && !told.add(key)) {
            return;
        }
        if (told.size() > MAX_TRACKED) {
            told.removeFirst();
        }
        queue.addLast(name);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (queue.isEmpty() || mc.getConnection() == null || message.isBlank()) {
            return;
        }
        if (--timer > 0) {
            return;
        }
        timer = delay.getInt();
        mc.getConnection().sendCommand("msg " + queue.removeFirst() + " " + message.getValue());
    }
}
