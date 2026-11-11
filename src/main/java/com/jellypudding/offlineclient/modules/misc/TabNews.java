package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.Notifier.JoinLeave;
import com.jellypudding.offlineclient.modules.misc.Notifier.ModeChange;
import com.jellypudding.offlineclient.modules.misc.Notifier.Pending;
import com.jellypudding.offlineclient.modules.misc.Notifier.Presence;
import com.jellypudding.offlineclient.modules.misc.Notifier.Rejoin;
import com.jellypudding.offlineclient.modules.misc.Notifier.ServerLine;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ClientLines;
import com.jellypudding.offlineclient.util.PlayerAlarm;
import com.jellypudding.offlineclient.util.TabChanges;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.GameType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

// What the tab list tells Notifier about the players on the server. Joins and leaves with the
// friends among them and the join alarm and players who vanish or change their game mode.
final class TabNews {

    private static final Minecraft MC = OfflineClient.MC;

    private enum JoinsLeaves {
        NONE,
        JOINS,
        LEAVES,
        BOTH
    }

    // What the tab list says about one player. The mode is null until the server sends one.
    private record TabEntry(String name, GameType mode, Component tabName, boolean listed) {
    }

    // A line the server wrote and the tick it came on.
    private record Seen(int tick, String text) {
    }

    // A tab change still waiting for its join or leave line.
    private record Unannounced(Presence change, int deadline) {
    }

    // How many tab changes one way came with a line and how many without.
    private static final class ChangeCounts {

        private int announced;
        private int silent;

        // A silent change only stands out where most changes are announced.
        boolean trusted() {
            return announced >= ANNOUNCED_TO_TRUST && announced > silent;
        }

        void clear() {
            announced = 0;
            silent = 0;
        }
    }

    // Tab entries this soon after joining are the players already online. Vanilla and
    // Paper send that list along with the login but not in the same order.
    private static final long JOIN_BURST_MILLIS = 2000;

    // How far apart in ticks a join or leave line and its tab change may arrive. Servers
    // send both in one tick and some plugins a little later.
    private static final int ANNOUNCE_WINDOW = 40;

    // Server lines kept for a tab change that arrives after its line.
    private static final int MAX_SERVER_LINES = 64;

    // Announced changes seen before a silent one counts as a vanish.
    private static final int ANNOUNCED_TO_TRUST = 2;

    // A join is worth several rings to wake a player who stepped away.
    private static final int JOIN_RINGS = 5;

    // Minutes before the same player sets the join alarm off again. A player who drops out and
    // reconnects is not news twice.
    private static final int JOIN_QUIET_MINUTES = 5;

    private final EnumSetting<JoinsLeaves> joinsLeaves = new EnumSetting<>("Joins and leaves",
        "Says when a player joins or leaves the server.", JoinsLeaves.NONE)
        .describe(JoinsLeaves.NONE, "Says nothing.")
        .describe(JoinsLeaves.JOINS, "Only joins.")
        .describe(JoinsLeaves.LEAVES, "Only leaves.")
        .describe(JoinsLeaves.BOTH, "Joins and leaves.");
    private final NumberSetting notificationDelay = new NumberSetting("Notification delay",
        "Ticks between one join or leave line and the next.", 0, 0, 100, 1, " ticks")
        .under(joinsLeaves, JoinsLeaves.JOINS, JoinsLeaves.LEAVES, JoinsLeaves.BOTH);
    private final BoolSetting simpleNotifications = new BoolSetting("Simple notifications",
        "Short join and leave lines with no client prefix.", true)
        .under(joinsLeaves, JoinsLeaves.JOINS, JoinsLeaves.LEAVES, JoinsLeaves.BOTH);
    private final BoolSetting friendJoins = new BoolSetting("Friend joins",
        "Says when a friend joins and which friends are on when you join.", true);

    private final PlayerAlarm joinAlarm = new PlayerAlarm("Join",
        "Rings when a player joins the server. The players already online when you join never ring it.",
        SoundEvents.BELL_BLOCK, JOIN_RINGS, JOIN_QUIET_MINUTES);

    private final BoolSetting vanish = new BoolSetting("Vanish",
        "Says when a player drops off the tab list without a leave message or comes back without a join message. "
            + "It stays quiet on a server that never announces them.", true);

    private final BoolSetting gameModes = new BoolSetting("Game modes",
        "Says when a player's game mode changes.", true);
    private final BoolSetting watchStaff = new BoolSetting("Watch staff",
        "Adds anyone who switches to creative or spectator to the join alarm names. With every name blank the"
            + " join alarm rings for them already.", false)
        .under(gameModes);

    private final Notifier notifier;
    // Where the netty thread hands its work to the game thread.
    private final Queue<Pending> queue;
    private final Deque<String> joinLeaveQueue = new ArrayDeque<>();
    // The tab list as the packets told it. The netty thread must not read the real one.
    // It fills this copy whilst the game thread clears it.
    private final Map<UUID, TabEntry> tab = Collections.synchronizedMap(new BoundedMap<>(Notifier.MAX_TRACKED));
    private final TabChanges tabChanges = new TabChanges();
    private final Deque<Seen> serverLines = new ArrayDeque<>();
    private final List<Unannounced> unannounced = new ArrayList<>();
    private final ChangeCounts joins = new ChangeCounts();
    private final ChangeCounts leaves = new ChangeCounts();
    private volatile long burstUntil;
    private int delayTimer;
    private boolean rollCallDue;

    TabNews(Notifier notifier, Queue<Pending> queue) {
        this.notifier = notifier;
        this.queue = queue;
    }

    // Joins and leaves with the friends among them and the join alarm.
    Setting<?>[] joinRows() {
        List<Setting<?>> rows = new ArrayList<>(List.of(joinsLeaves, notificationDelay, simpleNotifications,
            friendJoins));
        rows.addAll(List.of(joinAlarm.settings()));
        return rows.toArray(Setting<?>[]::new);
    }

    // Players who leave the list without a word or change their game mode.
    Setting<?>[] listRows() {
        return new Setting<?>[] {vanish, gameModes, watchStaff};
    }

    boolean alarmOn() {
        return joinAlarm.isOn();
    }

    void reset() {
        joinLeaveQueue.clear();
        tab.clear();
        forgetAlarm();
        forgetServer();
        burstUntil = 0;
        delayTimer = 0;
        rollCallDue = false;
    }

    void forgetServer() {
        serverLines.clear();
        unannounced.clear();
        joins.clear();
        leaves.clear();
    }

    void forgetAlarm() {
        joinAlarm.forget();
    }

    void stopAlarm() {
        joinAlarm.stop();
    }

    // Switched on in a world the list for this join came already. The game still holds it.
    void copyTabList() {
        ClientPacketListener connection = MC.getConnection();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            tab.put(info.getProfile().id(), new TabEntry(info.getProfile().name(), info.getGameMode(),
                info.getTabListDisplayName(), connection.getListedOnlinePlayers().contains(info)));
        }
        tabChanges.sync();
    }

    // Fired on the netty thread. The level must not be read here.
    void read(Packet<?> packet) {
        for (TabChanges.Change change : tabChanges.read(packet)) {
            queue.offer(new JoinLeave(change.name(), change.joined()));
        }
        switch (packet) {
            case ClientboundLoginPacket _ -> {
                tab.clear();
                burstUntil = System.currentTimeMillis() + JOIN_BURST_MILLIS;
                queue.offer(new Rejoin());
            }
            case ClientboundPlayerInfoUpdatePacket update -> onTabUpdate(update);
            case ClientboundPlayerInfoRemovePacket remove -> onTabRemove(remove);
            case ClientboundSystemChatPacket line when vanish.isOn() && !line.overlay() ->
                queue.offer(new ServerLine(line));
            default -> {
            }
        }
    }

    private void onTabUpdate(ClientboundPlayerInfoUpdatePacket packet) {
        EnumSet<Action> actions = packet.actions();
        boolean adding = actions.contains(Action.ADD_PLAYER);
        boolean burst = System.currentTimeMillis() < burstUntil;
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
            if (adding) {
                onTabAdd(entry, actions, burst);
            } else {
                onTabChange(entry, actions);
            }
        }
    }

    // The client lists a player only when the packet says to.
    private void onTabAdd(ClientboundPlayerInfoUpdatePacket.Entry entry, EnumSet<Action> actions, boolean burst) {
        if (entry.profile() == null) {
            return;
        }
        String name = entry.profile().name();
        boolean listed = actions.contains(Action.UPDATE_LISTED) && entry.listed();
        GameType mode = actions.contains(Action.UPDATE_GAME_MODE) ? entry.gameMode() : null;
        tab.put(entry.profileId(), new TabEntry(name, mode, entry.displayName(), listed));
        if (!burst && vanish.isOn() && listed) {
            queue.offer(new Presence(true, name, entry.displayName()));
        }
    }

    private void onTabChange(ClientboundPlayerInfoUpdatePacket.Entry entry, EnumSet<Action> actions) {
        TabEntry known = tab.get(entry.profileId());
        if (known == null) {
            return;
        }
        GameType mode = known.mode();
        if (actions.contains(Action.UPDATE_GAME_MODE)) {
            if (gameModes.isOn() && mode != null && mode != entry.gameMode()) {
                queue.offer(new ModeChange(known.name(), entry.gameMode()));
            }
            mode = entry.gameMode();
        }
        Component tabName = actions.contains(Action.UPDATE_DISPLAY_NAME) ? entry.displayName() : known.tabName();
        boolean listed = known.listed();
        if (actions.contains(Action.UPDATE_LISTED) && entry.listed() != listed) {
            listed = entry.listed();
            if (vanish.isOn()) {
                queue.offer(new Presence(listed, known.name(), tabName));
            }
        }
        tab.put(entry.profileId(), new TabEntry(known.name(), mode, tabName, listed));
    }

    private void onTabRemove(ClientboundPlayerInfoRemovePacket packet) {
        for (UUID id : packet.profileIds()) {
            TabEntry gone = tab.remove(id);
            // A player already off the list was reported when they were taken off it.
            if (gone != null && vanish.isOn() && gone.listed()) {
                queue.offer(new Presence(false, gone.name(), gone.tabName()));
            }
        }
    }

    private boolean wants(JoinsLeaves half) {
        return joinsLeaves.is(half) || joinsLeaves.is(JoinsLeaves.BOTH);
    }

    // The friends already on are named once the list that came with this join is complete.
    void onRejoin() {
        rollCallDue = true;
    }

    void tick() {
        expireUnannounced();
        drainJoinLeave();
        rollCall();
    }

    // The line goes out whilst that half is reported. A player joining the tab list also
    // sounds the join alarm and a friend joining is told on its own.
    void onJoinLeave(JoinLeave change) {
        boolean joinLine = wants(JoinsLeaves.JOINS);
        if (change.joined() ? joinLine : wants(JoinsLeaves.LEAVES)) {
            joinLeaveQueue.addLast(joinLine(change.name(), change.joined()));
        }
        if (!change.joined() || isSelf(change.name())) {
            return;
        }
        if (friendJoins.isOn() && !joinLine && Notifier.friend(change.name())) {
            notifier.say("§7Your friend §b" + change.name() + "§7 joined.");
        }
        if (notifier.alarms(change.name())) {
            joinAlarm.ring(change.name());
        }
    }

    // Names the friends already on once the list that came with a join is complete.
    private void rollCall() {
        if (!rollCallDue || System.currentTimeMillis() < burstUntil) {
            return;
        }
        rollCallDue = false;
        if (!friendJoins.isOn()) {
            return;
        }
        List<String> online = new ArrayList<>();
        synchronized (tab) {
            for (TabEntry entry : tab.values()) {
                if (entry.listed() && Notifier.friend(entry.name()) && !isSelf(entry.name())) {
                    online.add(entry.name());
                }
            }
        }
        if (!online.isEmpty()) {
            notifier.say("§7Friends online §b" + String.join(" §b", online) + "§7.");
        }
    }

    private void drainJoinLeave() {
        if (joinLeaveQueue.isEmpty()) {
            delayTimer = 0;
            return;
        }
        delayTimer++;
        while (delayTimer >= notificationDelay.getInt() && !joinLeaveQueue.isEmpty()) {
            delayTimer = 0;
            String line = joinLeaveQueue.removeFirst();
            if (!simpleNotifications.isOn()) {
                notifier.say(line);
            } else if (!ClientLines.take(Component.literal(line))) {
                MC.gui.hud.getChat().addClientSystemMessage(Component.literal(line));
            }
        }
    }

    private String joinLine(String name, boolean joined) {
        if (simpleNotifications.isOn()) {
            return joined ? "§7[§a+§7] §f" + name : "§7[§c-§7] §f" + name;
        }
        return "§f" + name + (joined ? " §7joined." : " §7left.");
    }

    // Watch staff takes anyone who turns to creative or spectator for staff.
    void reportMode(ModeChange change) {
        if (!gameModes.isOn() || isSelf(change.name()) || notifier.skip(change.name())) {
            return;
        }
        notifier.say("§b" + change.name() + " §7switched to §f" + change.mode().getLongDisplayName().getString()
            + "§7.");
        boolean staff = change.mode() == GameType.CREATIVE || change.mode() == GameType.SPECTATOR;
        if (watchStaff.isOn() && staff && joinAlarm.listenFor(change.name())) {
            notifier.say("§7Added §b" + change.name() + "§7 to the join alarm names.");
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
        }
    }

    // A line a player wrote is chat and never a join or leave message.
    void onServerLine(ClientboundSystemChatPacket packet) {
        if (ChatSender.lineOf(packet) != null) {
            return;
        }
        String text = ChatSender.plain(packet.content());
        serverLines.addLast(new Seen(notifier.clock(), text));
        if (serverLines.size() > MAX_SERVER_LINES) {
            serverLines.removeFirst();
        }
        Iterator<Unannounced> it = unannounced.iterator();
        while (it.hasNext()) {
            Presence change = it.next().change();
            if (names(text, change)) {
                it.remove();
                tally(change).announced++;
            }
        }
    }

    // Vanilla sends the join or leave line first. Paper sends the leave line after the tab change.
    void onPresence(Presence change) {
        for (Seen seen : serverLines) {
            if (notifier.clock() - seen.tick() <= ANNOUNCE_WINDOW && names(seen.text(), change)) {
                tally(change).announced++;
                return;
            }
        }
        unannounced.add(new Unannounced(change, notifier.clock() + ANNOUNCE_WINDOW));
    }

    // A change still without its line once the window closes came without one.
    private void expireUnannounced() {
        Iterator<Unannounced> it = unannounced.iterator();
        while (it.hasNext()) {
            Unannounced waiting = it.next();
            if (notifier.clock() < waiting.deadline()) {
                continue;
            }
            it.remove();
            Presence change = waiting.change();
            ChangeCounts tally = tally(change);
            boolean trusted = tally.trusted();
            tally.silent++;
            if (!trusted || !vanish.isOn() || isSelf(change.name()) || notifier.skip(change.name())) {
                continue;
            }
            notifier.say("§b" + change.name() + (change.shown()
                ? " §7reappeared without a join message." : " §7vanished without a leave message."));
        }
    }

    private ChangeCounts tally(Presence change) {
        return change.shown() ? joins : leaves;
    }

    // The line names the player by account or by the name the tab list shows.
    private static boolean names(String text, Presence change) {
        if (ChatUtil.wholeWordIndex(text, change.name(), 0) != -1) {
            return true;
        }
        if (change.tabName() == null) {
            return false;
        }
        String shown = ChatSender.plain(change.tabName()).trim();
        return !shown.isEmpty() && ChatUtil.wholeWordIndex(text, shown, 0) != -1;
    }

    private boolean isSelf(String name) {
        return name.equals(MC.player.getGameProfile().name());
    }
}
