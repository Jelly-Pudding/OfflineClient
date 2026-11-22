package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatPace;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.JoinWatch;
import com.jellypudding.offlineclient.util.SentLine;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.ShownChat;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.util.Util;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A spammer is only counted from lines whose writer cannot be made up. A player who types
// another name into their own line never gets that name muted.
public final class AntiSpam extends Module {

    private static final Pattern COUNTER = Pattern.compile(" x(\\d{1,8})$");

    private static final int MAX_PLAYERS = 256;

    private static final int MAX_WAITING = 16;

    private final NumberSetting depth = new NumberSetting("Depth",
        "How many recent lines are checked.", 4, 1, 20, 1);
    private final BoolSetting muteSpammers = new BoolSetting("Mute spammers",
        "Hides the chat of a player who writes too many lines too fast. A friend is never muted and switching"
            + " this off shows everyone again.", false);
    private final NumberSetting spamLines = new NumberSetting("Spam lines",
        "How many lines inside the spam time make a player a spammer.", 5, 2, 20, 1)
        .under(muteSpammers);
    private final NumberSetting spamTime = new NumberSetting("Spam time",
        "The seconds those lines have to come in.", 10, 1, 60, 1, "s")
        .under(muteSpammers);
    private final NumberSetting muteFor = new NumberSetting("Mute for",
        "Minutes a spammer stays hidden on your screen. Nought hides them until you leave the server.", 10, 0, 60,
        1, " minutes")
        .under(muteSpammers);
    private final TextSetting ignoreWith = new TextSetting("Ignore with",
        "A server command such as /ignore sent once for each spammer with their name on the end or where you put"
            + " {player}. The server goes on ignoring them after Mute for runs out. Empty only hides them on your"
            + " screen.", "")
        .under(muteSpammers);

    // Keyed by lower case name.
    private final Map<String, Deque<Long>> written = new BoundedMap<>(MAX_PLAYERS, true);
    // Keyed by lower case name.
    private final Map<String, Long> muted = new BoundedMap<>(MAX_PLAYERS);
    private final Deque<String> toIgnore = new ArrayDeque<>();
    // The common ignore commands lift an ignore when they are sent again. The names belong to the
    // server with this key.
    private final Set<String> ignored = Collections.newSetFromMap(new BoundedMap<>(MAX_PLAYERS));
    private String ignoredOn;
    private final JoinWatch joins = new JoinWatch();

    public AntiSpam() {
        super("AntiSpam", "Stacks repeated chat lines into one and can mute players who flood the chat.",
            Category.MISC);
        addSettings(depth, muteSpammers, spamLines, spamTime, muteFor, ignoreWith);
        searchTags("chat", "duplicate", "auto ignore", "auto mute", "flood");
    }

    @Override
    protected void onEnable() {
        forget();
        joins.accept();
    }

    @Override
    protected void onDisable() {
        forget();
    }

    private void forget() {
        written.clear();
        muted.clear();
        toIgnore.clear();
    }

    public Component fold(ChatComponent chat, Component message) {
        if (!isEnabled()) {
            return message;
        }
        String incoming = BetterChat.withoutStamp(message.getString());
        if (incoming.isBlank()) {
            return message;
        }
        String removed = ChatUtil.removeRecent(chat, depth.getInt(), line -> countAfter(line, incoming) > 0);
        if (removed == null) {
            return message;
        }
        return message.copy()
            .append(Component.literal(" x" + countAfter(removed, incoming)).withStyle(ChatFormatting.GRAY));
    }

    private static int countAfter(String line, String incoming) {
        String existing = BetterChat.withoutStamp(line);
        if (existing.equals(incoming)) {
            return 2;
        }
        Matcher matcher = COUNTER.matcher(existing);
        if (matcher.find() && existing.substring(0, matcher.start()).equals(incoming)) {
            return Integer.parseInt(matcher.group(1)) + 1;
        }
        return 0;
    }

    public boolean hides(Component message, GuiMessageSource source) {
        if (!isEnabled() || muted.isEmpty()) {
            return false;
        }
        String writer = ShownChat.writer(message, source);
        return writer != null && mutes(writer);
    }

    public boolean mutes(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Long until = isEnabled() && muteSpammers.isOn() ? muted.get(key) : null;
        if (until == null) {
            return false;
        }
        if (Util.getMillis() < until) {
            return true;
        }
        muted.remove(key);
        return false;
    }

    // Fired on the netty thread. The writer is read on the game thread where the tab list lives.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        if (muteSpammers.isOn() && ChatSender.isChat(packet)) {
            mc.schedule(() -> count(packet));
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (joins.joined() || !muteSpammers.isOn()) {
            forget();
        }
        String name = toIgnore.peekFirst();
        if (name == null || ignoreWith.isBlank()) {
            toIgnore.clear();
        } else if (ChatPace.RULE.say(SentLine.of(SentLine.naming(ignoreWith.getValue()), "player", name))) {
            toIgnore.removeFirst();
            ignored(ServerInfo.key()).add(name.toLowerCase(Locale.ROOT));
        }
    }

    private Set<String> ignored(String server) {
        if (!server.equals(ignoredOn)) {
            ignored.clear();
            ignoredOn = server;
        }
        return ignored;
    }

    private void count(Packet<?> packet) {
        if (!isEnabled() || !muteSpammers.isOn() || mc.player == null) {
            return;
        }
        String writer = ChatSender.certainWriter(packet);
        if (writer == null || writer.equalsIgnoreCase(mc.player.getGameProfile().name())
            || OfflineClient.INSTANCE.getFriendManager().isFriend(writer) || mutes(writer)) {
            return;
        }
        long now = Util.getMillis();
        long window = TimeUnit.SECONDS.toMillis(spamTime.getInt());
        String key = writer.toLowerCase(Locale.ROOT);
        Deque<Long> times = written.computeIfAbsent(key, unused -> new ArrayDeque<>());
        times.addLast(now);
        while (times.peekFirst() < now - window) {
            times.removeFirst();
        }
        if (times.size() >= spamLines.getInt()) {
            written.remove(key);
            mute(writer, key, now, times.size());
        }
    }

    private void mute(String name, String key, long now, int lines) {
        int minutes = muteFor.getInt();
        muted.put(key, minutes == 0 ? Long.MAX_VALUE : now + TimeUnit.MINUTES.toMillis(minutes));
        tell("muted §b" + EntityUtil.displayName(name) + "§7"
            + (minutes == 0 ? " until you leave" : " for " + Tally.counted(minutes, "minute")) + ". They wrote "
            + Tally.counted(lines, "line") + " in " + ChatUtil.duration(spamTime.getInt()) + ".");
        boolean told = ignored(ServerInfo.key()).contains(key) || toIgnore.contains(name);
        if (!ignoreWith.isBlank() && !told && toIgnore.size() < MAX_WAITING) {
            toIgnore.addLast(name);
        }
    }
}
