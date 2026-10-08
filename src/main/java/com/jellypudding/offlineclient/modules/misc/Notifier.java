package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.EntityAddedEvent;
import com.jellypudding.offlineclient.event.events.EntityDamageEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.event.events.TotemPopEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.AntiCheatGuess;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ClientLines;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.HurtReport;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.PlayerAlarm;
import com.jellypudding.offlineclient.util.PlayerMoves;
import com.jellypudding.offlineclient.util.PositionHistory;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
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

    // Unlisted entries are mostly server made figures such as NPCs showing a skin.
    private record JoinLeave(String name, boolean joined, boolean listed) implements Pending {
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

    // The server put you at this spot.
    private record Moved(Vec3 spot) implements Pending {
    }

    private record Swung(int entityId) implements Pending {
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

    // The last known name and kind and spot of one watched entity.
    private record Watched(String name, EntityType<?> type, Vec3 pos) {
    }

    // Where a pearl started and where it was last seen.
    private record Pearl(String owner, boolean own, Vec3 start, Vec3 pos) {
    }

    // A player in view whose arrival is told once their gear has come in.
    private record Arrival(int id, String name, int due) {
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

    // A join is worth several rings to wake a player who stepped away. Someone walking
    // into view is closer and needs fewer.
    private static final int JOIN_RINGS = 5;
    private static final int SIGHT_RINGS = 2;
    private static final int SPOTTED_RINGS = 1;

    // Minutes before the same player sets an alarm off again. A player who drops out and
    // reconnects is not news twice.
    private static final int JOIN_QUIET_MINUTES = 5;
    private static final int SIGHT_QUIET_MINUTES = 10;
    private static final int SPOTTED_QUIET_MINUTES = 2;

    // A pull back puts you within this many blocks of a spot you stood on in the last few seconds.
    private static final double PULL_BACK_REACH = 1;
    private static final int PULL_BACK_TICKS = 100;

    // Shorter than this is the server agreeing with where you are.
    private static final double LEAST_PULL_BACK = 0.1;

    // After a pull back line the next ones wait this long and are counted in one line.
    private static final int PULL_BACK_QUIET_TICKS = 60;

    // The equipment a player wears reaches the client a moment after the player does.
    private static final int GEAR_TICKS = 2;

    // A player who looked straight at you has looked away once they turn this many times further.
    private static final double LOOK_AWAY = 2;

    // No jump keeps a player up for this long without dropping.
    private static final int HOVER_TICKS = 40;

    // The same player is reported for the same breach at most this often.
    private static final int CHEAT_QUIET_TICKS = 30 * SharedConstants.TICKS_PER_SECOND;

    // A spot given as a step on any of these from where you stand is no pull back.
    private static final Set<Relative> STEPPED = EnumSet.of(Relative.X, Relative.Y, Relative.Z);

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
    private final BoolSetting details = new BoolSetting("Details",
        "Adds the distance and direction of each player and the gear they carry.", true)
        .under(visualRange);

    private final BoolSetting totemPops = new BoolSetting("Totem pops",
        "Says when a player pops a totem and how many they have popped.", true);
    private final BoolSetting ownTotems = new BoolSetting("Own totems",
        "Also report your own totem pops.", false)
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
    private final BoolSetting teleports = new BoolSetting("Teleports",
        "Says where a player in view lands after a long jump in one go.", false);
    private final NumberSetting teleportDistance = new NumberSetting("Teleport distance",
        "How far a player has to jump at once to be reported.", 16, 8, 128, 1, " blocks")
        .min(8)
        .under(teleports);

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
    private final BoolSetting friendJoins = new BoolSetting("Friend joins",
        "Says when a friend joins and which friends are on when you join.", true);

    private final PlayerAlarm joinAlarm = new PlayerAlarm("Join",
        "Rings when a player joins the server. The players already online when you join never ring it.",
        SoundEvents.BELL_BLOCK, JOIN_RINGS, JOIN_QUIET_MINUTES);
    private final PlayerAlarm sightAlarm = new PlayerAlarm("Sight",
        "Rings when a player enters your render distance. Anyone already there when you join or switch it on"
            + " rings it too.", SoundEvents.ANVIL_DESTROY, SIGHT_RINGS, SIGHT_QUIET_MINUTES);
    private final BoolSetting spotted = new BoolSetting("Spotted",
        "Says in chat when a player who can see you turns to face you.", false);
    private final NumberSetting spottedAngle = new NumberSetting("Spotted angle",
        "How far off pointing straight at you a player may look and still count.", 15, 2, 60, 1, " degrees")
        .min(1).max(180)
        .under(spotted);
    private final NumberSetting spottedRange = new NumberSetting("Spotted range",
        "How far away a player facing you still counts.", 64, 8, 256, 8, " blocks")
        .min(1)
        .under(spotted);
    private final PlayerAlarm spottedAlarm = new PlayerAlarm("Spotted",
        "Rings when a player who can see you turns to face you.",
        SoundEvents.ENDERMAN_STARE, SPOTTED_RINGS, SPOTTED_QUIET_MINUTES)
        .under(spotted);
    private final ActionSetting forgetAlarms = new ActionSetting("Forget alarmed players",
        "Lets every player set off the alarms again straight away.", this::forgetAlarms)
        .visibleWhen(() -> joinAlarm.isOn() || sightAlarm.isOn() || spottedAlarm.isOn());
    private final BoolSetting vanish = new BoolSetting("Vanish",
        "Says when a player drops off the tab list without a leave message or comes back without a join message. "
            + "It stays quiet on a server that never announces them.", true);

    private final BoolSetting gameModes = new BoolSetting("Game modes",
        "Says when a player's game mode changes.", true);
    private final BoolSetting watchStaff = new BoolSetting("Watch staff",
        "Adds anyone who switches to creative or spectator to the join alarm names. With every name blank the"
            + " join alarm rings for them already.", false)
        .under(gameModes);

    private final BoolSetting pullBacks = new BoolSetting("Pull backs",
        "Says when the server pulls you back to where you were.", true);
    private final BoolSetting antiCheat = new BoolSetting("Anti cheat",
        "Names the anti cheat the server seems to run when you join.", true);
    private final BoolSetting cheaters = new BoolSetting("Cheaters",
        "Says when a player near you flies or moves faster than the game allows.", false);
    private final NumberSetting speedLimit = new NumberSetting("Speed limit",
        "Blocks a second a player may cover before they are reported.", 20, 5, 60, 1, " bps")
        .min(1)
        .under(cheaters);
    private final NumberSetting swingLimit = new NumberSetting("Swing limit",
        "Swings a second a player may make before they are reported.", 20, 5, 40, 1, " per second")
        .min(1)
        .under(cheaters);
    private final BoolSetting hitsTaken = new BoolSetting("Hits taken",
        "Says in chat what hurt you and how far away and which way it came from.", false);

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
    private final ChangeCounts joins = new ChangeCounts();
    private final ChangeCounts leaves = new ChangeCounts();
    // The players in the world with you last tick whilst the sight alarm is on.
    private final Set<UUID> inSight = new HashSet<>();
    // The players facing you whose look has been told.
    private final Set<UUID> facing = new HashSet<>();
    private final List<Arrival> arrivals = new ArrayList<>();
    private final PositionHistory history = new PositionHistory();
    private final PlayerMoves moves = new PlayerMoves();
    private final Cooldowns<String> cheatQuiet = new Cooldowns<>();
    private final HurtReport hurts = new HurtReport(false);
    private final WorldWatch world = new WorldWatch();
    private volatile long burstUntil;
    private int delayTimer;
    private int clock;
    private int pullBackQuiet;
    private int pullBacksHeld;
    private boolean antiCheatTold;
    private boolean rollCallDue;

    public Notifier() {
        super("Notifier", "Tells you in chat and with alarms what other players and the server do.", Category.MISC);
        addSettings(visualRange, report, entities, details, totemPops, ownTotems, ignoreOthers,
            distanceCheck, playerRadius, pearls, ownPearls, teleports, teleportDistance, joinsLeaves,
            notificationDelay, simpleNotifications, friendJoins);
        addSettings(joinAlarm.settings());
        addSettings(sightAlarm.settings());
        addSettings(spotted, spottedAngle, spottedRange);
        addSettings(spottedAlarm.settings());
        addSettings(forgetAlarms, vanish, gameModes, watchStaff, pullBacks, antiCheat, cheaters, speedLimit,
            swingLimit, hitsTaken, badEffects, warnEffects, ignoreFriends, sound);
        searchTags("visual range", "totem pop", "alert", "pearl", "join", "leave", "vanish",
            "game mode", "weakness", "poison", "wither", "effect", "alarm", "render distance",
            "watch list", "setback", "lagback", "anti cheat", "teleport", "cheater", "damage", "staff",
            "friend", "spotted");
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
        joinAlarm.stop();
        sightAlarm.stop();
        spottedAlarm.stop();
    }

    private void reset() {
        queue.clear();
        joinLeaveQueue.clear();
        watched.clear();
        flying.clear();
        popLines.clear();
        effectLevels.clear();
        tab.clear();
        inSight.clear();
        forgetAlarms();
        forgetServer();
        forgetWorld();
        world.forget();
        burstUntil = 0;
        delayTimer = 0;
        rollCallDue = false;
    }

    private void forgetAlarms() {
        joinAlarm.forget();
        sightAlarm.forget();
        spottedAlarm.forget();
    }

    private void forgetServer() {
        serverLines.clear();
        unannounced.clear();
        joins.clear();
        leaves.clear();
        cheatQuiet.clear();
        antiCheatTold = false;
    }

    // What belongs to one world. A new dimension resends every entity and the pop counts start again.
    private void forgetWorld() {
        watched.clear();
        flying.clear();
        popLines.clear();
        facing.clear();
        arrivals.clear();
        history.clear();
        moves.clear();
        hurts.clear();
        pullBackQuiet = 0;
        pullBacksHeld = 0;
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
            // A server pull back names the spot outright. A spot given as a step from where you are
            // is some other move.
            case ClientboundPlayerPositionPacket packet when pullBacks.isOn()
                && Collections.disjoint(packet.relatives(), STEPPED) ->
                queue.offer(new Moved(packet.change().position()));
            case ClientboundSwingAnimationPacket packet when cheaters.isOn() ->
                queue.offer(new Swung(packet.entityId()));
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
        queue.offer(new JoinLeave(name, true, listed));
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
            queue.offer(new JoinLeave(gone.name(), false, gone.listed()));
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
        String name = entity.getName().getString();
        watched.put(entity.getId(), new Watched(name, entity.getType(), entity.position()));
        // Everything already nearby shows up in one burst right after joining.
        if (mc.player.tickCount < SETTLE_TICKS || !visualRange.isOn() || report.is(Report.DESPAWN)) {
            return;
        }
        if (entity instanceof Player && details.isOn()) {
            arrivals.add(new Arrival(entity.getId(), name, clock + GEAR_TICKS));
        } else {
            announce(name, entity.getType(), entity.position(), true, null);
        }
    }

    @Subscribe
    private void onDamage(EntityDamageEvent event) {
        if (hitsTaken.isOn()) {
            hurts.hit(event);
        }
        if (event.getEntity() instanceof Player) {
            moves.hurt(event.getEntity().getId());
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        clock++;
        if (world.changed()) {
            forgetWorld();
        }
        Pending pending;
        while ((pending = queue.poll()) != null) {
            switch (pending) {
                case JoinLeave change -> onJoinLeave(change);
                case ModeChange change -> reportMode(change);
                case Presence change -> onPresence(change);
                case ServerLine line -> onServerLine(line.packet());
                case Rejoin _ -> {
                    forgetServer();
                    rollCallDue = true;
                }
                case Moved moved -> onMoved(moved.spot());
                case Swung swung -> moves.swung(swung.entityId());
            }
        }
        expireUnannounced();
        drainJoinLeave();
        rollCall();
        watchSight();
        watchFacing();
        sweepWatched();
        tellArrivals();
        sweepPearls();
        checkEffects();
        if (teleports.isOn() || cheaters.isOn()) {
            moves.tick();
            reportJumps();
            judgeCheaters();
        } else {
            moves.clear();
        }
        endPullBackQuiet();
        tellAntiCheat();
        for (String line : hurts.tick()) {
            say(line);
        }
        history.record(mc.player.position());
    }

    // The line goes out whilst that half is reported. A player joining the tab list also
    // sounds the join alarm and a friend joining is told on its own.
    private void onJoinLeave(JoinLeave change) {
        boolean joinLine = wants(JoinsLeaves.JOINS);
        if (change.joined() ? joinLine : wants(JoinsLeaves.LEAVES)) {
            joinLeaveQueue.addLast(joinLine(change.name(), change.joined()));
        }
        if (!change.joined() || !change.listed() || isSelf(change.name())) {
            return;
        }
        if (friendJoins.isOn() && !joinLine && friend(change.name())) {
            say("§7Your friend §b" + change.name() + "§7 joined.");
        }
        if (!skip(change.name())) {
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
                if (entry.listed() && friend(entry.name()) && !isSelf(entry.name())) {
                    online.add(entry.name());
                }
            }
        }
        if (!online.isEmpty()) {
            say("§7Friends online §b" + String.join(" §b", online) + "§7.");
        }
    }

    // A player arrives on the first tick they share the world with you. Anyone already there
    // when you join or switch the sight alarm on arrives at once.
    private void watchSight() {
        if (!sightAlarm.isOn()) {
            inSight.clear();
            return;
        }
        Set<UUID> present = new HashSet<>();
        for (AbstractClientPlayer player : mc.level.players()) {
            if (!EntityUtil.isOtherPlayer(player)) {
                continue;
            }
            present.add(player.getUUID());
            String name = player.getGameProfile().name();
            if (!inSight.contains(player.getUUID()) && !skip(name) && !Modules.isBot(player)) {
                sightAlarm.ring(name);
            }
        }
        inSight.clear();
        inSight.addAll(present);
    }

    // A player counts on the tick they turn to face you with nothing in the way. They are
    // told once until they look well away or lose sight of you.
    private void watchFacing() {
        if (!spotted.isOn()) {
            facing.clear();
            return;
        }
        Set<UUID> still = new HashSet<>();
        for (AbstractClientPlayer player : mc.level.players()) {
            if (!EntityUtil.isRealPlayer(player) || skip(player.getGameProfile().name())) {
                continue;
            }
            double distance = player.distanceTo(mc.player);
            double angle = EntityUtil.lookAngle(player, mc.player);
            boolean told = facing.contains(player.getUUID());
            double allowed = told ? spottedAngle.getValue() * LOOK_AWAY : spottedAngle.getValue();
            // The game's own sight test gives up past 128 blocks and this clip has no limit.
            if (distance > spottedRange.getValue() || angle > allowed || !BlockUtil.canSee(player.getEyePosition())) {
                continue;
            }
            still.add(player.getUUID());
            if (!told) {
                String name = player.getGameProfile().name();
                say("§b" + name + " §7is looking at you from §b"
                    + Notice.away(distance, mc.player.position(), player.position()) + "§7.");
                spottedAlarm.ring(name);
            }
        }
        facing.clear();
        facing.addAll(still);
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
            if (!simpleNotifications.isOn()) {
                say(line);
            } else if (!ClientLines.take(Component.literal(line))) {
                mc.gui.hud.getChat().addClientSystemMessage(Component.literal(line));
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
    private void reportMode(ModeChange change) {
        if (!gameModes.isOn() || isSelf(change.name()) || skip(change.name())) {
            return;
        }
        say("§b" + change.name() + " §7switched to §f" + change.mode().getLongDisplayName().getString() + "§7.");
        boolean staff = change.mode() == GameType.CREATIVE || change.mode() == GameType.SPECTATOR;
        if (watchStaff.isOn() && staff && joinAlarm.listenFor(change.name())) {
            say("§7Added §b" + change.name() + "§7 to the join alarm names.");
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
        }
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
            ChangeCounts tally = tally(change);
            boolean trusted = tally.trusted();
            tally.silent++;
            if (!trusted || !vanish.isOn() || isSelf(change.name()) || skip(change.name())) {
                continue;
            }
            say("§b" + change.name() + (change.shown()
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
        return name.equals(mc.player.getGameProfile().name());
    }

    // The server put you somewhere. A pull back lands within a block of where you stood in the
    // last few seconds whilst a pearl or a command sends you somewhere new.
    private void onMoved(Vec3 spot) {
        Vec3 was = history.ticksAgo(0);
        if (!pullBacks.isOn() || was == null || mc.player.tickCount < SETTLE_TICKS || mc.player.isPassenger()
            || !history.wasNear(spot, PULL_BACK_REACH, PULL_BACK_TICKS)) {
            return;
        }
        double distance = was.distanceTo(spot);
        if (distance < LEAST_PULL_BACK) {
            return;
        }
        if (pullBackQuiet > 0) {
            pullBacksHeld++;
            return;
        }
        pullBackQuiet = PULL_BACK_QUIET_TICKS;
        say("§7The server pulled you back §b" + String.format(Locale.ROOT, "%.1f", distance) + "§7 blocks.");
    }

    // The pull backs held back in the quiet time go out in one line which starts another.
    private void endPullBackQuiet() {
        if (pullBackQuiet == 0 || --pullBackQuiet > 0 || pullBacksHeld == 0) {
            return;
        }
        say("§7The server pulled you back §b" + Tally.counted(pullBacksHeld, "more time") + "§7.");
        pullBacksHeld = 0;
        pullBackQuiet = PULL_BACK_QUIET_TICKS;
    }

    // Once a join and once more after the module is switched on.
    private void tellAntiCheat() {
        if (antiCheatTold || !antiCheat.isOn() || !AntiCheatGuess.INSTANCE.settled()) {
            return;
        }
        antiCheatTold = true;
        say(AntiCheatGuess.INSTANCE.found() ? "§7The server seems to run " + AntiCheatGuess.INSTANCE.summary() + "§7."
            : "§7No anti cheat showed itself on this server.");
    }

    private void reportJumps() {
        if (!teleports.isOn()) {
            return;
        }
        for (PlayerMoves.Jump jump : moves.jumps(teleportDistance.getValue())) {
            String name = jump.player().getGameProfile().name();
            if (skip(name) || Modules.isBot(jump.player())) {
                continue;
            }
            say(Component.literal("§b" + name + " §7jumped §b" + Math.round(jump.from().distanceTo(jump.to()))
                + "§7 blocks to ").append(ChatUtil.walkLink(BlockPos.containing(jump.to()))).append("§7."));
        }
    }

    // Each player is reported for the same breach once in a while.
    private void judgeCheaters() {
        if (!cheaters.isOn()) {
            return;
        }
        cheatQuiet.tick();
        for (AbstractClientPlayer player : mc.level.players()) {
            String name = player.getGameProfile().name();
            if (!EntityUtil.isRealPlayer(player) || skip(name)) {
                continue;
            }
            double speed = moves.speed(player);
            if (speed > speedLimit.getValue()) {
                cheat(player, "speed", "§b" + name + " §7moved at §c" + Math.round(speed) + "§7 blocks a second.");
            }
            int hovering = moves.hovering(player);
            if (hovering > HOVER_TICKS) {
                cheat(player, "flight", "§b" + name + " §7has stayed up in the air for §c"
                    + ChatUtil.duration((double) hovering / SharedConstants.TICKS_PER_SECOND) + "§7.");
            }
            int swings = moves.swings(player);
            if (swings > swingLimit.getValue()) {
                cheat(player, "swings", "§b" + name + " §7swung §c" + swings + "§7 times in a second.");
            }
        }
    }

    private void cheat(Player player, String breach, String line) {
        String key = player.getUUID() + " " + breach;
        if (cheatQuiet.contains(key)) {
            return;
        }
        cheatQuiet.put(key, CHEAT_QUIET_TICKS);
        say(line);
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
                announce(gone.name(), gone.type(), gone.pos(), false, null);
            }
        }
    }

    // A player whose arrival waited for their gear. One who left again at once goes untold.
    private void tellArrivals() {
        Iterator<Arrival> it = arrivals.iterator();
        while (it.hasNext()) {
            Arrival arrival = it.next();
            if (clock < arrival.due()) {
                continue;
            }
            it.remove();
            if (mc.level.getEntity(arrival.id()) instanceof Player player && visualRange.isOn()) {
                announce(arrival.name(), EntityTypes.PLAYER, player.position(), true, player);
            }
        }
    }

    // Details add where a player is and for one still in view what they carry.
    private void announce(String name, EntityType<?> type, Vec3 pos, boolean arrived, Player seen) {
        if (type != EntityTypes.PLAYER) {
            say("§f" + type.getDescription().getString() + " §7has "
                + (arrived ? "spawned" : "despawned") + " at " + coords(pos) + "§7.");
            return;
        }
        if (skip(name)) {
            return;
        }
        String more = "";
        if (details.isOn()) {
            more = " §b" + Notice.away(mc.player.position().distanceTo(pos), mc.player.position(), pos) + "§7"
                + (seen == null ? "" : gear(seen));
        }
        say("§b" + name + " §7" + (arrived ? "entered" : "left") + " your render distance" + more + "§7.");
    }

    // What a player carries in a few words such as holding a mace in full netherite.
    private static String gear(Player player) {
        StringBuilder words = new StringBuilder();
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty()) {
            words.append(" holding §f").append(ChatUtil.withArticle(ChatUtil.words(held.getItem()))).append("§7");
        }
        List<String> kinds = new ArrayList<>();
        int pieces = 0;
        boolean elytra = false;
        for (EquipmentSlot slot : ItemUtil.ARMOR_SLOTS) {
            ItemStack worn = player.getItemBySlot(slot);
            if (worn.isEmpty()) {
                continue;
            }
            pieces++;
            if (worn.has(DataComponents.GLIDER)) {
                elytra = true;
                continue;
            }
            String kind = armourKind(worn);
            if (!kinds.contains(kind)) {
                kinds.add(kind);
            }
        }
        if (pieces == 0) {
            return words.append(" in no armour").toString();
        }
        if (!kinds.isEmpty()) {
            boolean full = kinds.size() == 1 && pieces == ItemUtil.ARMOR_SLOTS.size();
            words.append(full ? " in full §f" + kinds.getFirst() : " in §f" + String.join(" §7and §f", kinds)
                + "§7 armour").append("§7");
        }
        return elytra ? words.append(" with §fan elytra§7").toString() : words.toString();
    }

    // The material of a piece of armour such as netherite and the whole name of anything else worn.
    private static String armourKind(ItemStack worn) {
        String words = ChatUtil.words(worn.getItem());
        int last = words.lastIndexOf(' ');
        return worn.is(ItemTags.TRIMMABLE_ARMOR) && last > 0 ? words.substring(0, last) : words;
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

    // A death is reported wherever it happens. Only pops heed the distance check.
    @Subscribe
    private void onTotemPop(TotemPopEvent event) {
        Player player = event.getPlayer();
        if (!totemPops.isOn() || !reportsPops(player)) {
            return;
        }
        if (event.isDeath()) {
            replace(player.getUUID(), label(player) + "died after popping " + describe(event.getCount()) + ".");
            popLines.remove(player.getUUID());
            return;
        }
        if (distanceCheck.isOn() && mc.player.distanceTo(player) > playerRadius.getValue()) {
            return;
        }
        replace(player.getUUID(), label(player) + "popped " + describe(event.getCount()) + ".");
    }

    private boolean reportsPops(Player player) {
        if (player == mc.player) {
            return ownTotems.isOn();
        }
        boolean friend = friend(player.getGameProfile().name());
        return !(ignoreFriends.isOn() && friend) && !(ignoreOthers.isOn() && !friend);
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
        ClientLines.removeRecent(line -> ChatFormatting.stripFormatting(line).contains(wanted));
    }

    private static boolean friend(String name) {
        return OfflineClient.INSTANCE.getFriendManager().isFriend(name);
    }

    private boolean skip(String name) {
        return ignoreFriends.isOn() && friend(name);
    }

    private void say(String message) {
        say(Component.literal(message));
    }

    private void say(Component message) {
        ChatUtil.component(message);
        if (sound.isOn()) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f));
        }
    }
}
