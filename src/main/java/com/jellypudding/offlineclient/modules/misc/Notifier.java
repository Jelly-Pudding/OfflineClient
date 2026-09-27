package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.EntityAddedEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

public final class Notifier extends Module {

    private enum Report {
        SPAWN,
        DESPAWN,
        BOTH
    }

    private enum JoinsLeaves {
        NONE,
        JOINS,
        LEAVES,
        BOTH
    }

    // Work the netty thread hands to the game thread in the order it arrived.
    private sealed interface Pending {
    }

    private record TotemPop(int entityId) implements Pending {
    }

    private record JoinLeave(String name, boolean joined) implements Pending {
    }

    private record ModeChange(String name, GameType mode) implements Pending {
    }

    // A player put on the tab list or taken off it. The tab name is null unless the server set one.
    private record Presence(boolean shown, String name, Component tabName) implements Pending {
    }

    private record ServerLine(ClientboundSystemChatPacket packet) implements Pending {
    }

    private record Rejoin() implements Pending {
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
    private static final class Tally {

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

    // The last known name and kind and spot of one watched entity.
    private record Watched(String name, EntityType<?> type, Vec3 pos) {
    }

    // Where a pearl started and where it was last seen.
    private record Pearl(String owner, boolean own, Vec3 start, Vec3 pos) {
    }

    // Ticks after a join or respawn whilst the entities and effects the server sends stay quiet.
    private static final int SETTLE_TICKS = 40;

    // Packets held whilst the game thread is busy. Anything past this is dropped.
    private static final int MAX_PENDING = 4096;

    private static final int MAX_TRACKED = 512;

    // How far back a totem line is looked for when it is replaced.
    private static final int REPLACE_DEPTH = 64;

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

    // A deep tone that stands apart from the soft ping.
    private static final float WARNING_PITCH = 0.5f;

    private final BoolSetting visualRange = new BoolSetting("Visual range",
        "Say when something enters or leaves your render distance.", true);
    private final EnumSetting<Report> report = new EnumSetting<>("Report",
        "Which half of visual range is reported.", Report.BOTH)
        .describe(Report.SPAWN, "Only arrivals.")
        .describe(Report.DESPAWN, "Only departures.")
        .describe(Report.BOTH, "Arrivals and departures.")
        .under(visualRange);
    private final RegistryListSetting<EntityType<?>> entities = new RegistryListSetting<>("Entities",
        "Kinds of entity visual range watches.", BuiltInRegistries.ENTITY_TYPE,
        List.of(EntityTypes.PLAYER))
        .under(visualRange);

    private final BoolSetting totemPops = new BoolSetting("Totem pops",
        "Says when a player pops a totem and how many they have popped.", true);
    private final BoolSetting ownTotems = new BoolSetting("Own totems",
        "Also count your own totem pops.", false)
        .under(totemPops);
    private final BoolSetting ignoreOthers = new BoolSetting("Ignore others",
        "Only report totem pops from friends.", false)
        .under(totemPops);
    private final BoolSetting distanceCheck = new BoolSetting("Distance check",
        "Only report totem pops from players close to you.", false)
        .under(totemPops);
    private final NumberSetting playerRadius = new NumberSetting("Player radius",
        "How far away a totem pop can be and still be reported.", 30, 1, 50, 1, " blocks")
        .min(1)
        .under(distanceCheck);

    private final BoolSetting pearls = new BoolSetting("Pearls",
        "Says where every thrown ender pearl lands.", true);
    private final BoolSetting ownPearls = new BoolSetting("Own pearls",
        "Also report your own pearls.", true)
        .under(pearls);

    private final EnumSetting<JoinsLeaves> joinsLeaves = new EnumSetting<>("Joins and leaves",
        "Say when a player joins or leaves the server.", JoinsLeaves.NONE)
        .describe(JoinsLeaves.NONE, "Say nothing.")
        .describe(JoinsLeaves.JOINS, "Only joins.")
        .describe(JoinsLeaves.LEAVES, "Only leaves.")
        .describe(JoinsLeaves.BOTH, "Joins and leaves.");
    private final NumberSetting notificationDelay = new NumberSetting("Notification delay",
        "Ticks between one join or leave line and the next.", 0, 0, 100, 1, " ticks")
        .min(0)
        .under(joinsLeaves, JoinsLeaves.JOINS, JoinsLeaves.LEAVES, JoinsLeaves.BOTH);
    private final BoolSetting simpleNotifications = new BoolSetting("Simple notifications",
        "Short join and leave lines with no client prefix.", true)
        .under(joinsLeaves, JoinsLeaves.JOINS, JoinsLeaves.LEAVES, JoinsLeaves.BOTH);
    private final BoolSetting vanish = new BoolSetting("Vanish",
        "Says when a player drops off the tab list without a leave message or comes back without a join message. "
            + "It stays quiet on a server that never announces them.", true);

    private final BoolSetting gameModes = new BoolSetting("Game modes",
        "Says when a player's game mode changes.", true);

    private final BoolSetting badEffects = new BoolSetting("Bad effects",
        "Warns you in chat and with a deep tone when you gain one of the effects below.", true);
    private final RegistryListSetting<MobEffect> warnEffects = new RegistryListSetting<>("Effects",
        "The effects that set off the warning. Click to pick them.", BuiltInRegistries.MOB_EFFECT,
        List.of(MobEffects.WEAKNESS.value(), MobEffects.POISON.value(), MobEffects.WITHER.value()))
        .under(badEffects);

    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Stay quiet about people on your friend list.", false);
    private final BoolSetting sound = new BoolSetting("Sound",
        "Play a soft ping with every message.", false);

    private final Queue<Pending> queue = new LinkedBlockingQueue<>(MAX_PENDING);
    private final Deque<String> joinLeaveQueue = new ArrayDeque<>();
    // The client removes some entities without a packet. None of these maps can be
    // trusted to empty itself.
    private final Map<Integer, Watched> watched = new BoundedMap<>(MAX_TRACKED);
    private final Map<Integer, Pearl> flying = new BoundedMap<>(MAX_TRACKED);
    // Totem pops per player since their last death.
    private final Map<UUID, Integer> pops = new BoundedMap<>(MAX_TRACKED);
    // The last totem line printed for a player. The next one replaces it.
    private final Map<UUID, String> popLines = new BoundedMap<>(MAX_TRACKED);
    // The level of each effect you had last tick. It outlives a dimension change and the
    // effects the server sends again then are nothing new.
    private final Map<Holder<MobEffect>, Integer> effectLevels = new HashMap<>();
    // The tab list as the packets told it. The netty thread must not read the real one.
    // It fills this copy whilst the game thread clears it.
    private final Map<UUID, TabEntry> tab = Collections.synchronizedMap(new BoundedMap<>(MAX_TRACKED));
    private final Deque<Seen> serverLines = new ArrayDeque<>();
    private final List<Unannounced> unannounced = new ArrayList<>();
    private final Tally joins = new Tally();
    private final Tally leaves = new Tally();
    private final WorldWatch world = new WorldWatch();
    private volatile long burstUntil;
    private int delayTimer;
    private int clock;

    public Notifier() {
        super("Notifier", "Chat messages when players come and go and when totems pop.", Category.MISC);
        addSettings(visualRange, report, entities, totemPops, ownTotems, ignoreOthers,
            distanceCheck, playerRadius, pearls, ownPearls, joinsLeaves, notificationDelay,
            simpleNotifications, vanish, gameModes, badEffects, warnEffects, ignoreFriends, sound);
        searchTags("visual range", "totem pop", "alert", "pearl", "join", "leave", "vanish",
            "game mode", "weakness", "poison", "wither", "effect");
    }

    @Override
    protected void onEnable() {
        reset();
        // Switched on in a world the list for this join came already. The game still holds it.
        if (inGame()) {
            copyTabList();
            rememberEffects();
        }
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        queue.clear();
        joinLeaveQueue.clear();
        watched.clear();
        flying.clear();
        pops.clear();
        popLines.clear();
        effectLevels.clear();
        tab.clear();
        forgetServer();
        world.forget();
        burstUntil = 0;
        delayTimer = 0;
    }

    private void forgetServer() {
        serverLines.clear();
        unannounced.clear();
        joins.clear();
        leaves.clear();
    }

    private void copyTabList() {
        ClientPacketListener connection = mc.getConnection();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            tab.put(info.getProfile().id(), new TabEntry(info.getProfile().name(), info.getGameMode(),
                info.getTabListDisplayName(), connection.getListedOnlinePlayers().contains(info)));
        }
    }

    // Fired on the netty thread. The level must not be read here.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        switch (event.getPacket()) {
            case ClientboundLoginPacket _ -> {
                tab.clear();
                burstUntil = System.currentTimeMillis() + JOIN_BURST_MILLIS;
                queue.offer(new Rejoin());
            }
            case ClientboundPlayerInfoUpdatePacket packet -> onTabUpdate(packet);
            case ClientboundPlayerInfoRemovePacket packet -> onTabRemove(packet);
            case ClientboundSystemChatPacket packet when vanish.isOn() && !packet.overlay() ->
                queue.offer(new ServerLine(packet));
            case ClientboundEntityEventPacket packet when packet.getEventId() == EntityEvent.PROTECTED_FROM_DEATH ->
                queue.offer(new TotemPop(packet.entityId));
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
        if (burst) {
            return;
        }
        if (wants(JoinsLeaves.JOINS)) {
            queue.offer(new JoinLeave(name, true));
        }
        if (vanish.isOn() && listed) {
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
            if (gone == null) {
                continue;
            }
            if (wants(JoinsLeaves.LEAVES)) {
                queue.offer(new JoinLeave(gone.name(), false));
            }
            // A player already off the list was reported when they were taken off it.
            if (vanish.isOn() && gone.listed()) {
                queue.offer(new Presence(false, gone.name(), gone.tabName()));
            }
        }
    }

    private boolean wants(JoinsLeaves half) {
        return joinsLeaves.is(half) || joinsLeaves.is(JoinsLeaves.BOTH);
    }

    @Subscribe
    private void onEntityAdded(EntityAddedEvent event) {
        Entity entity = event.getEntity();
        if (entity == mc.player) {
            return;
        }
        if (pearls.isOn() && entity instanceof ThrownEnderpearl pearl) {
            flying.put(pearl.getId(), new Pearl(EntityUtil.throwerName(pearl), pearl.getOwner() == mc.player,
                pearl.position(), pearl.position()));
        }
        if (!entities.contains(entity.getType())) {
            return;
        }
        watched.put(entity.getId(), new Watched(entity.getName().getString(),
            entity.getType(), entity.position()));
        // Everything already nearby shows up in one burst right after joining.
        if (mc.player.tickCount < SETTLE_TICKS) {
            return;
        }
        if (visualRange.isOn() && !report.is(Report.DESPAWN)) {
            announce(entity.getName().getString(), entity.getType(), entity.position(), true);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        clock++;
        if (world.changed()) {
            // A new dimension resends every entity. Queued totem pops name ids from the last one.
            queue.removeIf(pending -> pending instanceof TotemPop);
            watched.clear();
            flying.clear();
            pops.clear();
            popLines.clear();
        }
        Pending pending;
        while ((pending = queue.poll()) != null) {
            switch (pending) {
                case TotemPop pop -> handleTotem(pop.entityId());
                case JoinLeave change -> joinLeaveQueue.addLast(joinLine(change.name(), change.joined()));
                case ModeChange change -> reportMode(change);
                case Presence change -> onPresence(change);
                case ServerLine line -> onServerLine(line.packet());
                case Rejoin _ -> forgetServer();
            }
        }
        expireUnannounced();
        drainJoinLeave();
        sweepWatched();
        sweepPearls();
        if (totemPops.isOn()) {
            checkDeaths();
        }
        checkEffects();
    }

    // Warns once when an effect is gained or grows stronger. The first ticks of a new
    // player object only take stock of what the server sends along with it.
    private void checkEffects() {
        Map<Holder<MobEffect>, MobEffectInstance> active = mc.player.getActiveEffectsMap();
        boolean settling = mc.player.tickCount < SETTLE_TICKS;
        for (MobEffectInstance effect : active.values()) {
            Integer before = effectLevels.get(effect.getEffect());
            boolean gained = before == null || effect.getAmplifier() > before;
            if (gained && !settling && badEffects.isOn() && warnEffects.contains(effect.getEffect().value())) {
                warn(effect);
            }
        }
        rememberEffects();
    }

    private void rememberEffects() {
        effectLevels.clear();
        mc.player.getActiveEffectsMap().forEach((type, effect) -> effectLevels.put(type, effect.getAmplifier()));
    }

    private void warn(MobEffectInstance effect) {
        String name = effect.getEffect().value().getDisplayName().getString();
        if (effect.getAmplifier() > 0) {
            name += " " + (effect.getAmplifier() + 1);
        }
        String time = effect.isInfiniteDuration() ? "" : " §7for §b"
            + MobEffectUtil.formatDuration(effect, 1, mc.level.tickRateManager().tickrate()).getString();
        ChatUtil.message("§7You have §c" + name + time + "§7.");
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, WARNING_PITCH));
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
            if (simpleNotifications.isOn()) {
                mc.gui.hud.getChat().addClientSystemMessage(Component.literal(line));
            } else {
                say(line);
            }
        }
    }

    private String joinLine(String name, boolean joined) {
        if (simpleNotifications.isOn()) {
            return joined ? "§7[§a+§7] §f" + name : "§7[§c-§7] §f" + name;
        }
        return "§f" + name + (joined ? " §7joined." : " §7left.");
    }

    private void reportMode(ModeChange change) {
        if (!gameModes.isOn() || isSelf(change.name()) || skip(change.name())) {
            return;
        }
        say("§b" + change.name() + " §7switched to §f" + change.mode().getLongDisplayName().getString() + "§7.");
    }

    // A line a player wrote is chat and never a join or leave message.
    private void onServerLine(ClientboundSystemChatPacket packet) {
        if (ChatSender.lineOf(packet) != null) {
            return;
        }
        String text = ChatSender.plain(packet.content());
        serverLines.addLast(new Seen(clock, text));
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
    private void onPresence(Presence change) {
        for (Seen seen : serverLines) {
            if (clock - seen.tick() <= ANNOUNCE_WINDOW && names(seen.text(), change)) {
                tally(change).announced++;
                return;
            }
        }
        unannounced.add(new Unannounced(change, clock + ANNOUNCE_WINDOW));
    }

    // A change still without its line once the window closes came without one.
    private void expireUnannounced() {
        Iterator<Unannounced> it = unannounced.iterator();
        while (it.hasNext()) {
            Unannounced waiting = it.next();
            if (clock < waiting.deadline()) {
                continue;
            }
            it.remove();
            Presence change = waiting.change();
            Tally tally = tally(change);
            boolean trusted = tally.trusted();
            tally.silent++;
            if (!trusted || !vanish.isOn() || isSelf(change.name()) || skip(change.name())) {
                continue;
            }
            say("§b" + change.name() + (change.shown()
                ? " §7reappeared without a join message." : " §7vanished without a leave message."));
        }
    }

    private Tally tally(Presence change) {
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
        return name.equals(mc.player.getGameProfile().name());
    }

    // The client drops entities without a packet. The level is the only truth.
    private void sweepWatched() {
        if (watched.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, Watched>> it = watched.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Watched> entry = it.next();
            Entity entity = mc.level.getEntity(entry.getKey());
            if (entity != null && entity.isAlive()) {
                entry.setValue(new Watched(entry.getValue().name(), entry.getValue().type(),
                    entity.position()));
                continue;
            }
            it.remove();
            if (visualRange.isOn() && !report.is(Report.SPAWN)) {
                Watched gone = entry.getValue();
                announce(gone.name(), gone.type(), gone.pos(), false);
            }
        }
    }

    private void announce(String name, EntityType<?> type, Vec3 pos, boolean arrived) {
        if (type == EntityTypes.PLAYER) {
            if (skip(name)) {
                return;
            }
            say("§b" + name + " §7" + (arrived ? "entered" : "left")
                + " your render distance.");
            return;
        }
        say("§f" + type.getDescription().getString() + " §7has "
            + (arrived ? "spawned" : "despawned") + " at " + coords(pos) + "§7.");
    }

    private static String coords(Vec3 pos) {
        return "§b" + Math.round(pos.x) + " " + Math.round(pos.y) + " " + Math.round(pos.z);
    }

    private void sweepPearls() {
        if (flying.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, Pearl>> it = flying.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Pearl> entry = it.next();
            Pearl pearl = entry.getValue();
            if (mc.level.getEntity(entry.getKey()) instanceof ThrownEnderpearl live) {
                String owner = pearl.owner().equals(EntityUtil.UNKNOWN_THROWER)
                    ? EntityUtil.throwerName(live) : pearl.owner();
                entry.setValue(new Pearl(owner, live.getOwner() == mc.player,
                    pearl.start(), live.position()));
                continue;
            }
            it.remove();
            if (!pearls.isOn() || (pearl.own() && !ownPearls.isOn()) || skip(pearl.owner())) {
                continue;
            }
            long travelled = Math.round(pearl.start().distanceTo(pearl.pos()));
            long away = Math.round(mc.player.position().distanceTo(pearl.pos()));
            say("§b" + pearl.owner() + "§7's pearl landed at " + coords(pearl.pos())
                + " §7after §b" + travelled + " §7blocks and is §b"
                + away + " §7blocks away.");
        }
    }

    private void handleTotem(int entityId) {
        if (!totemPops.isOn() || !(mc.level.getEntity(entityId) instanceof Player player)) {
            return;
        }
        if (player == mc.player && !ownTotems.isOn()) {
            return;
        }
        String name = player.getGameProfile().name();
        boolean friend = OfflineClient.INSTANCE.getFriendManager().isFriend(name);
        if (player != mc.player && ((ignoreFriends.isOn() && friend)
            || (ignoreOthers.isOn() && !friend))) {
            return;
        }
        int count = pops.merge(player.getUUID(), 1, Integer::sum);
        if (distanceCheck.isOn() && mc.player.distanceTo(player) > playerRadius.getValue()) {
            return;
        }
        replace(player.getUUID(), label(player) + "popped " + describe(count) + ".");
    }

    private void checkDeaths() {
        if (pops.isEmpty()) {
            return;
        }
        for (Player player : mc.level.players()) {
            Integer count = pops.get(player.getUUID());
            if (count == null || !(player.isDeadOrDying() || player.deathTime > 0)) {
                continue;
            }
            pops.remove(player.getUUID());
            replace(player.getUUID(), label(player) + "died after popping " + describe(count) + ".");
            popLines.remove(player.getUUID());
        }
    }

    private String label(Player player) {
        return player == mc.player ? "§7You " : "§b" + player.getGameProfile().name() + " §7";
    }

    private static String describe(int count) {
        return count == 1 ? "§b1 §7totem" : "§b" + count + " §7totems";
    }

    // One player running count updates in place rather than stacking lines.
    private void replace(UUID player, String message) {
        String previous = popLines.put(player, message);
        if (previous != null) {
            drop(previous);
        }
        say(message);
    }

    private static void drop(String message) {
        String wanted = ChatFormatting.stripFormatting(message);
        ChatUtil.removeRecent(mc.gui.hud.getChat(), REPLACE_DEPTH,
            line -> ChatFormatting.stripFormatting(line).contains(wanted));
    }

    private boolean skip(String name) {
        return ignoreFriends.isOn() && OfflineClient.INSTANCE.getFriendManager().isFriend(name);
    }

    private void say(String message) {
        ChatUtil.message(message);
        if (sound.isOn()) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f));
        }
    }
}
