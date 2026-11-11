package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.EntityDamageEvent;
import com.jellypudding.offlineclient.event.events.TotemPopEvent;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ClientLines;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Holes;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.PlayerAlarm;
import com.jellypudding.offlineclient.util.PlayerMoves;
import com.jellypudding.offlineclient.util.Strangers;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

// What Notifier tells about the players and things round you. What comes into view and leaves it
// and totem pops and pearls and long jumps and who looks at you or burrows or carries a bed close
// by and who seems to cheat.
final class PlayerNews {

    private static final Minecraft MC = OfflineClient.MC;

    private enum Report {
        SPAWN,
        DESPAWN,
        BOTH
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

    // How far back a totem line is looked for when it is replaced.
    private static final int REPLACE_DEPTH = 64;

    // Someone walking into view is closer than a player who joins and needs fewer rings.
    private static final int SIGHT_RINGS = 2;
    private static final int SPOTTED_RINGS = 1;

    // Minutes before the same player sets an alarm off again.
    private static final int SIGHT_QUIET_MINUTES = 10;
    private static final int SPOTTED_QUIET_MINUTES = 2;

    // The equipment a player wears reaches the client a moment after the player does.
    private static final int GEAR_TICKS = 2;

    // A player who looked straight at you has looked away once they turn this many times further.
    private static final double LOOK_AWAY = 2;

    // No jump keeps a player up for this long without dropping.
    private static final int HOVER_TICKS = 40;

    // The same player is reported for the same breach at most this often.
    private static final int CHEAT_QUIET_TICKS = 30 * SharedConstants.TICKS_PER_SECOND;

    private final BoolSetting visualRange = new BoolSetting("Visual range",
        "Says when something enters or leaves your render distance.", true);
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
        "Also reports your own totem pops.", false)
        .under(totemPops);
    private final BoolSetting ignoreOthers = new BoolSetting("Ignore others",
        "Only reports totem pops from friends.", false)
        .under(totemPops);
    private final BoolSetting distanceCheck = new BoolSetting("Distance check",
        "Only reports totem pops from players close to you.", false)
        .under(totemPops);
    private final NumberSetting playerRadius = new NumberSetting("Player radius",
        "How far away a totem pop can be and still be reported.", 30, 1, 50, 1, " blocks")
        .under(distanceCheck);

    private final BoolSetting pearls = new BoolSetting("Pearls",
        "Says where every thrown ender pearl lands.", true);
    private final BoolSetting ownPearls = new BoolSetting("Own pearls",
        "Also reports your own pearls.", true)
        .under(pearls);
    private final BoolSetting teleports = new BoolSetting("Teleports",
        "Says where a player in view lands after a long jump in one go.", false);
    private final NumberSetting teleportDistance = new NumberSetting("Teleport distance",
        "How far a player has to jump at once to be reported.", 16, 8, 128, 1, " blocks")
        .under(teleports);

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

    private final BoolSetting burrows = new BoolSetting("Burrows",
        "Says in chat when a player who is not a friend steps into a blast proof block close to you.", false);
    private final NumberSetting burrowRange = new NumberSetting("Burrow range",
        "How far away a burrowing player is still told.", 16, 4, 64, 1, " blocks")
        .min(1)
        .under(burrows);
    private final BoolSetting bedCarriers = new BoolSetting("Bed carriers",
        "Says in chat when a player who is not a friend comes close to you with a bed in hand.", false);
    private final NumberSetting bedRange = new NumberSetting("Bed range",
        "How close a player with a bed has to come.", 12, 4, 64, 1, " blocks")
        .min(1)
        .under(bedCarriers);

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

    private final Notifier notifier;
    // The client removes some entities without a packet. None of these maps can be
    // trusted to empty itself.
    private final Map<Integer, Watched> watched = new BoundedMap<>(Notifier.MAX_TRACKED);
    private final Map<Integer, Pearl> flying = new BoundedMap<>(Notifier.MAX_TRACKED);
    // The last totem line printed for a player. The next one replaces it.
    private final Map<UUID, String> popLines = new BoundedMap<>(Notifier.MAX_TRACKED);
    // The players in the world with you last tick whilst the sight alarm is on.
    private final Set<UUID> inSight = new HashSet<>();
    // The players facing you whose look has been told.
    private final Set<UUID> facing = new HashSet<>();
    private final List<Arrival> arrivals = new ArrayList<>();
    // The players whose burrow has been told and those whose bed has.
    private final Set<UUID> burrowed = new HashSet<>();
    private final Set<UUID> bedders = new HashSet<>();
    private final PlayerMoves moves = new PlayerMoves();
    private final Cooldowns<String> cheatQuiet = new Cooldowns<>();

    PlayerNews(Notifier notifier) {
        this.notifier = notifier;
    }

    // Visual range and totem pops and pearls and teleports.
    Setting<?>[] inViewRows() {
        return new Setting<?>[] {visualRange, report, entities, details, totemPops, ownTotems, ignoreOthers,
            distanceCheck, playerRadius, pearls, ownPearls, teleports, teleportDistance};
    }

    // The sight alarm and players who turn to face you.
    Setting<?>[] lookRows() {
        List<Setting<?>> rows = new ArrayList<>(List.of(sightAlarm.settings()));
        rows.addAll(List.of(spotted, spottedAngle, spottedRange));
        rows.addAll(List.of(spottedAlarm.settings()));
        return rows.toArray(Setting<?>[]::new);
    }

    // Players close by who burrow or carry a bed.
    Setting<?>[] nearRows() {
        return new Setting<?>[] {burrows, burrowRange, bedCarriers, bedRange};
    }

    Setting<?>[] cheaterRows() {
        return new Setting<?>[] {cheaters, speedLimit, swingLimit};
    }

    // True whilst the sight alarm or the spotted alarm is on.
    boolean alarmsOn() {
        return sightAlarm.isOn() || spottedAlarm.isOn();
    }

    // True whilst burrows or bed carriers close by are told.
    boolean watchesStrangers() {
        return burrows.isOn() || bedCarriers.isOn();
    }

    // True whilst the swings of other players are counted.
    boolean countsSwings() {
        return cheaters.isOn();
    }

    void reset() {
        inSight.clear();
        forgetAlarms();
        forgetServer();
        forgetWorld();
    }

    void forgetAlarms() {
        sightAlarm.forget();
        spottedAlarm.forget();
    }

    void stopAlarms() {
        sightAlarm.stop();
        spottedAlarm.stop();
    }

    void forgetServer() {
        cheatQuiet.clear();
    }

    // What belongs to one world. A new dimension resends every entity and the pop counts start again.
    void forgetWorld() {
        watched.clear();
        flying.clear();
        popLines.clear();
        facing.clear();
        arrivals.clear();
        burrowed.clear();
        bedders.clear();
        moves.clear();
    }

    void onEntityAdded(Entity entity) {
        if (entity == MC.player) {
            return;
        }
        if (pearls.isOn() && entity instanceof ThrownEnderpearl pearl) {
            flying.put(pearl.getId(), new Pearl(EntityUtil.throwerName(pearl), pearl.getOwner() == MC.player,
                pearl.position(), pearl.position()));
        }
        if (!entities.contains(entity.getType())) {
            return;
        }
        String name = entity.getName().getString();
        watched.put(entity.getId(), new Watched(name, entity.getType(), entity.position()));
        // Everything already nearby shows up in one burst right after joining.
        if (MC.player.tickCount < Notifier.SETTLE_TICKS || !visualRange.isOn() || report.is(Report.DESPAWN)) {
            return;
        }
        if (entity instanceof Player && details.isOn()) {
            arrivals.add(new Arrival(entity.getId(), name, notifier.clock() + GEAR_TICKS));
        } else {
            announce(name, entity.getType(), entity.position(), true, null);
        }
    }

    void hurt(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            moves.hurt(event.getEntity().getId());
        }
    }

    void swung(int entityId) {
        moves.swung(entityId);
    }

    void tick() {
        watchSight();
        watchFacing();
        watchDoing(burrows, burrowRange, burrowed, Holes::burrowed, "burrowed");
        watchDoing(bedCarriers, bedRange, bedders, Strangers::holdsBed, "is holding a bed");
        sweepWatched();
        tellArrivals();
        sweepPearls();
        if (teleports.isOn() || cheaters.isOn()) {
            moves.tick();
            reportJumps();
            judgeCheaters();
        } else {
            moves.clear();
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
        for (AbstractClientPlayer player : MC.level.players()) {
            if (!EntityUtil.isOtherPlayer(player)) {
                continue;
            }
            present.add(player.getUUID());
            String name = player.getGameProfile().name();
            if (!inSight.contains(player.getUUID()) && notifier.alarms(name) && !Modules.isBot(player)) {
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
        for (AbstractClientPlayer player : MC.level.players()) {
            if (!EntityUtil.isRealPlayer(player) || notifier.skip(player.getGameProfile().name())) {
                continue;
            }
            double distance = player.distanceTo(MC.player);
            double angle = EntityUtil.lookAngle(player, MC.player);
            boolean told = facing.contains(player.getUUID());
            double allowed = told ? spottedAngle.getValue() * LOOK_AWAY : spottedAngle.getValue();
            // The game's own sight test gives up past 128 blocks and this clip has no limit.
            if (distance > spottedRange.getValue() || angle > allowed || !BlockUtil.canSee(player.getEyePosition())) {
                continue;
            }
            still.add(player.getUUID());
            if (!told) {
                String name = player.getGameProfile().name();
                notifier.say("§b" + name + " §7is looking at you from §b"
                    + Notice.away(distance, MC.player.position(), player.position()) + "§7.");
                if (notifier.alarms(name)) {
                    spottedAlarm.ring(name);
                }
            }
        }
        facing.clear();
        facing.addAll(still);
    }

    // Tells each player once as they start to do something within the range. They are told
    // again only after they stopped or went out of range.
    private void watchDoing(BoolSetting on, NumberSetting range, Set<UUID> told, Predicate<Player> doing,
                            String did) {
        if (!on.isOn()) {
            told.clear();
            return;
        }
        Set<UUID> still = new HashSet<>();
        for (AbstractClientPlayer player : MC.level.players()) {
            double distance = player.distanceTo(MC.player);
            if (distance > range.getValue() || !Strangers.counts(player, notifier.enemiesOnly())
                || !doing.test(player)) {
                continue;
            }
            still.add(player.getUUID());
            if (!told.contains(player.getUUID())) {
                notifier.say("§b" + player.getGameProfile().name() + " §7" + did + " §b"
                    + Notice.away(distance, MC.player.position(), player.position()) + "§7.");
            }
        }
        told.clear();
        told.addAll(still);
    }

    private void reportJumps() {
        if (!teleports.isOn()) {
            return;
        }
        for (PlayerMoves.Jump jump : moves.jumps(teleportDistance.getValue())) {
            String name = jump.player().getGameProfile().name();
            if (notifier.skip(name) || Modules.isBot(jump.player())) {
                continue;
            }
            notifier.say(Component.literal("§b" + name + " §7jumped §b" + Math.round(jump.from().distanceTo(jump.to()))
                + "§7 blocks to ").append(ChatUtil.walkLink(BlockPos.containing(jump.to()))).append("§7."));
        }
    }

    // Each player is reported for the same breach once in a while.
    private void judgeCheaters() {
        if (!cheaters.isOn()) {
            return;
        }
        cheatQuiet.tick();
        for (AbstractClientPlayer player : MC.level.players()) {
            String name = player.getGameProfile().name();
            if (!EntityUtil.isRealPlayer(player) || notifier.skip(name)) {
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
        notifier.say(line);
    }

    // The client drops entities without a packet. The level is the only truth.
    private void sweepWatched() {
        if (watched.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, Watched>> it = watched.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Watched> entry = it.next();
            Entity entity = MC.level.getEntity(entry.getKey());
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
            if (notifier.clock() < arrival.due()) {
                continue;
            }
            it.remove();
            if (MC.level.getEntity(arrival.id()) instanceof Player player && visualRange.isOn()) {
                announce(arrival.name(), EntityTypes.PLAYER, player.position(), true, player);
            }
        }
    }

    // Details add where a player is and for one still in view what they carry.
    private void announce(String name, EntityType<?> type, Vec3 pos, boolean arrived, Player seen) {
        if (type != EntityTypes.PLAYER) {
            notifier.say("§f" + type.getDescription().getString() + " §7has "
                + (arrived ? "spawned" : "despawned") + " at " + coords(pos) + "§7.");
            return;
        }
        if (notifier.skip(name)) {
            return;
        }
        String more = "";
        if (details.isOn()) {
            more = " §b" + Notice.away(MC.player.position().distanceTo(pos), MC.player.position(), pos) + "§7"
                + (seen == null ? "" : gear(seen));
        }
        notifier.say("§b" + name + " §7" + (arrived ? "entered" : "left") + " your render distance" + more + "§7.");
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
            if (MC.level.getEntity(entry.getKey()) instanceof ThrownEnderpearl live) {
                String owner = pearl.owner().equals(EntityUtil.UNKNOWN_THROWER)
                    ? EntityUtil.throwerName(live) : pearl.owner();
                entry.setValue(new Pearl(owner, live.getOwner() == MC.player,
                    pearl.start(), live.position()));
                continue;
            }
            it.remove();
            if (!pearls.isOn() || (pearl.own() && !ownPearls.isOn()) || notifier.skip(pearl.owner())) {
                continue;
            }
            long travelled = Math.round(pearl.start().distanceTo(pearl.pos()));
            long away = Math.round(MC.player.position().distanceTo(pearl.pos()));
            notifier.say("§b" + pearl.owner() + "§7's pearl landed at " + coords(pearl.pos())
                + " §7after §b" + travelled + " §7blocks and is §b"
                + away + " §7blocks away.");
        }
    }

    // A death is reported wherever it happens. Only pops heed the distance check.
    void onTotemPop(TotemPopEvent event) {
        Player player = event.getPlayer();
        if (!totemPops.isOn() || !reportsPops(player)) {
            return;
        }
        if (event.isDeath()) {
            replace(player.getUUID(), label(player) + "died after popping " + describe(event.getCount()) + ".");
            popLines.remove(player.getUUID());
            return;
        }
        if (distanceCheck.isOn() && MC.player.distanceTo(player) > playerRadius.getValue()) {
            return;
        }
        replace(player.getUUID(), label(player) + "popped " + describe(event.getCount()) + ".");
    }

    private boolean reportsPops(Player player) {
        if (player == MC.player) {
            return ownTotems.isOn();
        }
        String name = player.getGameProfile().name();
        return !notifier.skip(name) && !(ignoreOthers.isOn() && !Notifier.friend(name));
    }

    private String label(Player player) {
        return player == MC.player ? "§7You " : "§b" + EntityUtil.displayNameOf(player) + " §7";
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
        notifier.say(message);
    }

    private static void drop(String message) {
        String wanted = ChatFormatting.stripFormatting(message);
        ChatUtil.removeRecent(MC.gui.hud.getChat(), REPLACE_DEPTH,
            line -> ChatFormatting.stripFormatting(line).contains(wanted));
        ClientLines.removeRecent(line -> ChatFormatting.stripFormatting(line).contains(wanted));
    }
}
