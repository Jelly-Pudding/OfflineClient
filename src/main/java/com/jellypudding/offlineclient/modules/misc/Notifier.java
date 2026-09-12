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
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
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

    private enum Kind {
        TOTEM,
        JOIN,
        LEAVE
    }

    private record Pending(Kind kind, int entityId, String name) {
    }

    // What we last knew about one entity we are watching.
    private record Watched(String name, EntityType<?> type, Vec3 pos) {
    }

    // Where a pearl started and the last place we saw it.
    private record Pearl(String owner, boolean own, Vec3 start, Vec3 pos) {
    }

    private static final String UNKNOWN_OWNER = "Someone";

    // Ticks after joining during which entities already nearby stay quiet.
    private static final int JOIN_GRACE_TICKS = 40;

    // Packets held whilst the game thread is busy. Anything past this is dropped.
    private static final int MAX_PENDING = 4096;

    private static final int MAX_TRACKED = 512;

    // How far back a totem line is looked for when it is replaced.
    private static final int REPLACE_DEPTH = 64;

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
        .min(1).max(100)
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
        .min(0).max(1000)
        .under(joinsLeaves, JoinsLeaves.JOINS, JoinsLeaves.LEAVES, JoinsLeaves.BOTH);
    private final BoolSetting simpleNotifications = new BoolSetting("Simple notifications",
        "Short join and leave lines with no client prefix.", true)
        .under(joinsLeaves, JoinsLeaves.JOINS, JoinsLeaves.LEAVES, JoinsLeaves.BOTH);

    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Stay quiet about people on your friend list.", false);
    private final BoolSetting sound = new BoolSetting("Sound",
        "Play a soft ping with every message.", false);

    private final Queue<Pending> queue = new LinkedBlockingQueue<>(MAX_PENDING);
    private final Deque<String> joinLeaveQueue = new ArrayDeque<>();
    // Entity id to what we last knew about it.
    private final Map<Integer, Watched> watched = bounded(MAX_TRACKED);
    private final Map<Integer, Pearl> flying = bounded(MAX_TRACKED);
    // Totem pops per player since their last death.
    private final Map<UUID, Integer> pops = bounded(MAX_TRACKED);
    // The last totem line printed for a player. The next one replaces it.
    private final Map<UUID, String> popLines = bounded(MAX_TRACKED);
    // Names from the tab list. The netty thread must not read the real one.
    private final Map<UUID, String> tabNames = bounded(MAX_TRACKED);
    private ClientLevel lastLevel;
    private boolean firstTabPacket = true;
    private int delayTimer;

    // The client removes some entities without a packet.
    // None of these maps can be trusted to empty itself.
    private static <K, V> Map<K, V> bounded(int max) {
        return new LinkedHashMap<K, V>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        };
    }

    public Notifier() {
        super("Notifier", "Chat messages when players come and go and when totems pop.", Category.MISC);
        addSettings(visualRange, report, entities, totemPops, ownTotems, ignoreOthers,
            distanceCheck, playerRadius, pearls, ownPearls, joinsLeaves, notificationDelay,
            simpleNotifications, ignoreFriends, sound);
        searchTags("visual range", "totem pop", "alert", "pearl", "join", "leave");
    }

    @Override
    protected void onEnable() {
        reset();
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
        tabNames.clear();
        lastLevel = null;
        firstTabPacket = true;
        delayTimer = 0;
    }

    // Fired on the netty thread. The level must not be read here.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        switch (event.getPacket()) {
            case ClientboundPlayerInfoUpdatePacket packet -> onTabUpdate(packet);
            case ClientboundPlayerInfoRemovePacket packet -> onTabRemove(packet);
            case ClientboundEntityEventPacket packet when packet.getEventId() == EntityEvent.PROTECTED_FROM_DEATH ->
                queue.offer(new Pending(Kind.TOTEM, packet.entityId, null));
            default -> {
            }
        }
    }

    // The first packet after login lists everyone already on the server.
    private void onTabUpdate(ClientboundPlayerInfoUpdatePacket packet) {
        if (!packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
            return;
        }
        boolean first = firstTabPacket;
        firstTabPacket = false;
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.newEntries()) {
            if (entry.profile() == null) {
                continue;
            }
            tabNames.put(entry.profileId(), entry.profile().name());
            if (!first && wants(JoinsLeaves.JOINS)) {
                queue.offer(new Pending(Kind.JOIN, 0, entry.profile().name()));
            }
        }
    }

    private void onTabRemove(ClientboundPlayerInfoRemovePacket packet) {
        for (UUID id : packet.profileIds()) {
            String name = tabNames.remove(id);
            if (name != null && wants(JoinsLeaves.LEAVES)) {
                queue.offer(new Pending(Kind.LEAVE, 0, name));
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
            flying.put(pearl.getId(), new Pearl(ownerName(pearl), pearl.getOwner() == mc.player,
                pearl.position(), pearl.position()));
        }
        if (!entities.contains(entity.getType())) {
            return;
        }
        watched.put(entity.getId(), new Watched(entity.getName().getString(),
            entity.getType(), entity.position()));
        // Everything already nearby shows up in one burst right after joining.
        if (mc.player.tickCount < JOIN_GRACE_TICKS) {
            return;
        }
        if (visualRange.isOn() && !report.is(Report.DESPAWN)) {
            announce(entity.getName().getString(), entity.getType(), entity.position(), true);
        }
    }

    private static String ownerName(ThrownEnderpearl pearl) {
        Entity owner = pearl.getOwner();
        return owner == null ? UNKNOWN_OWNER : owner.getName().getString();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (mc.level != lastLevel) {
            // A new dimension resends every entity. Queued ids belong to the old one.
            queue.clear();
            watched.clear();
            flying.clear();
            pops.clear();
            popLines.clear();
            lastLevel = mc.level;
        }
        Pending pending;
        while ((pending = queue.poll()) != null) {
            switch (pending.kind()) {
                case TOTEM -> handleTotem(pending);
                case JOIN -> joinLeaveQueue.addLast(joinLine(pending.name(), true));
                case LEAVE -> joinLeaveQueue.addLast(joinLine(pending.name(), false));
            }
        }
        drainJoinLeave();
        sweepWatched();
        sweepPearls();
        if (totemPops.isOn()) {
            checkDeaths();
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
                String owner = pearl.owner().equals(UNKNOWN_OWNER) ? ownerName(live) : pearl.owner();
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

    private void handleTotem(Pending pending) {
        if (!totemPops.isOn() || !(mc.level.getEntity(pending.entityId()) instanceof Player player)) {
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
        String wanted = plain(message);
        List<GuiMessage> all = mc.gui.hud.getChat().allMessages;
        int max = Math.min(REPLACE_DEPTH, all.size());
        for (int i = 0; i < max; i++) {
            if (plain(all.get(i).content().getString()).contains(wanted)) {
                all.remove(i);
                mc.gui.hud.getChat().refreshTrimmedMessages();
                return;
            }
        }
    }

    private static String plain(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '§') {
                i++;
            } else {
                out.append(text.charAt(i));
            }
        }
        return out.toString();
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
