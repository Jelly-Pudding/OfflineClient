package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindSource;
import com.jellypudding.offlineclient.config.SpawnerLedger;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ChunkDataEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.gui.FindsScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.FindLines;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.AlertSound;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChunkScanner;
import com.jellypudding.offlineclient.util.ChunkWindow;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.OwnDigs;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.SpawnerMob;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.WorldWatch;
import com.jellypudding.offlineclient.worldgen.SpawnerRooms;
import com.jellypudding.offlineclient.worldgen.SpawnerRooms.Place;
import com.jellypudding.offlineclient.worldgen.SpawnerRooms.Room;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

// Finds the spawners players have been near and the rooms whose spawner they mined. A spawner
// only counts down whilst a player stands within its reach and the server sends the count with
// its chunk. Every count is kept with how long you stood near it since and a change your own
// time there cannot explain is someone else's. Dungeons and spider corridors are cave air
// inside and a block a player breaks there leaves plain air. A room you dug in or came near
// may hold your own work and is never told as someone else's.
public final class SpawnerFinder extends Module implements FindSource {

    // What a find shows. A find of higher rank takes the spot of a lower one.
    private enum Evidence {
        ROOM(0),
        SPAWNER(1),
        LIT(1),
        MINED(2);

        private final int rank;

        Evidence(int rank) {
            this.rank = rank;
        }

        boolean ofSpawner() {
            return this == SPAWNER || this == LIT;
        }
    }

    // The kind a find is saved under such as lit dungeon spawner with stash. A stash is storage
    // a player brought for a spawner and a chest is one still standing in a room. The kind alone
    // tells how to draw and filter a find after a restart.
    private record Kind(Place place, Evidence evidence, boolean marked) {

        private static final Map<String, Kind> BY_NAME = new HashMap<>();

        static {
            for (Place place : Place.values()) {
                for (Evidence evidence : Evidence.values()) {
                    for (boolean marked : new boolean[] {false, true}) {
                        Kind kind = new Kind(place, evidence, marked);
                        BY_NAME.put(kind.name(), kind);
                    }
                }
            }
        }

        // Null for a kind this build does not know.
        static Kind parse(String name) {
            return BY_NAME.get(name);
        }

        // Such as mined dungeon with chest or spider corridor without spawner.
        String name() {
            String label = place.label().toLowerCase(Locale.ROOT);
            String what = switch (evidence) {
                case SPAWNER -> label + " spawner";
                case LIT -> "lit " + label + " spawner";
                case ROOM -> room(label);
                case MINED -> place == Place.MINESHAFT ? room(label) + " without spawner" : "mined " + room(label);
            };
            return !marked ? what : what + (evidence.ofSpawner() ? " with stash" : " with chest");
        }

        // Only dungeons and spider corridors are told as rooms.
        private String room(String label) {
            return switch (place) {
                case DUNGEON -> "dungeon";
                case MINESHAFT -> "spider corridor";
                default -> label + " room";
            };
        }
    }

    // A spawner as its reading arrived and what happened to it since. The reading is the count
    // or the trial state the server sent. Own ticks is how long you stood within its reach before
    // it came or less than nought until your spot here is known. Ticks near is how long you
    // stood within its reach after. Live is a change sent whilst its chunk stayed loaded.
    private static final class Arrival {

        private final boolean trial;
        private final int reading;
        private final int reach;
        private final int spawnRange;
        private final Place place;
        private final EntityType<?> mob;
        private final boolean live;
        private int ownTicks;
        private int ticksNear;
        private boolean judged;

        private Arrival(boolean trial, int reading, int reach, int spawnRange, Place place, EntityType<?> mob,
                        boolean live) {
            this.trial = trial;
            this.reading = reading;
            this.reach = reach;
            this.spawnRange = spawnRange;
            this.place = place;
            this.mob = mob;
            this.live = live;
        }
    }

    // A find waiting for the chunks round it and the entities in them before its room is read.
    // An untouched spawner waits here for the broken blocks check with no evidence yet.
    private static final class Survey {

        private final BlockPos pos;
        private final Arrival arrival;
        private final Evidence evidence;
        private final boolean live;
        private final int deadline;
        private int readyAt = -1;

        private Survey(BlockPos pos, Arrival arrival, Evidence evidence, boolean live, int deadline) {
            this.pos = pos;
            this.arrival = arrival;
            this.evidence = evidence;
            this.live = live;
            this.deadline = deadline;
        }
    }

    // Trial spawners only leave waiting for players once players arrive.
    private static final Set<TrialSpawnerState> FOUGHT = EnumSet.of(TrialSpawnerState.ACTIVE,
        TrialSpawnerState.WAITING_FOR_REWARD_EJECTION, TrialSpawnerState.EJECTING_REWARD,
        TrialSpawnerState.COOLDOWN);
    private static final TrialSpawnerState[] TRIAL_STATES = TrialSpawnerState.values();
    // BaseSpawner sends this block event when a spawn cycle ends and the next count begins.
    private static final int SPAWN_EVENT = 1;
    // Half a minute of your spots. A chunk held back by lag arrives well within it.
    private static final int TRAIL_TICKS = 600;
    // Your spot is known once the first second in a world has passed.
    private static final int SPOT_KNOWN_TICKS = 20;
    // A live sign came from whoever stood near within the last second.
    private static final int LIVE_TICKS = 20;
    // The server may count a few ticks for you before your spot reaches this side.
    private static final int SLACK_TICKS = 5;
    // A stalled frame drops client ticks the server still counts. A spell near a spawner may
    // run a quarter longer than it looked.
    private static final int STALL_PART = 4;
    // Your spot here and on the server differ by a little.
    private static final double REACH_SLACK = 2;
    // How long a spawner a player stands at keeps quiet before it is told again.
    private static final int LIVE_QUIET_TICKS = 6000;
    // Entities follow their chunk within a tick or two.
    private static final int SETTLE_TICKS = 20;
    // A find whose neighbours never come is told with what is loaded.
    private static final int SURVEY_TICKS = 100;
    // A spider corridor reaches this far along from its spawner.
    private static final int ROOM_REACH = 24;
    // A room reaches into the chunks beside the one that keeps it. A change anywhere in a chunk
    // reads its neighbours again.
    private static final int ROOM_BORDER = 16;
    // A player's light is looked for in the spawn volume and up to the top of a dungeon.
    private static final int LIGHT_BELOW = 1;
    private static final int LIGHT_ABOVE = 3;

    private final ChoiceListSetting places = new ChoiceListSetting("Places",
        "Which places spawners and rooms are reported and marked in. Placed or changed is a spawner a player "
            + "set down or gave another mob.",
        SpawnerFinder::placeLabels, placeLabels());
    private final BoolSetting brokenBlocks = new BoolSetting("Broken blocks",
        "Also reports untouched spawners in dungeons mineshafts and strongholds where a player broke a block.",
        true);
    private final BoolSetting litSpawners = new BoolSetting("Lit spawners",
        "Says when a player lit up the room of a zombie skeleton spider or cave spider spawner and marks it in "
            + "its own colour.", true);
    private final BoolSetting storageNearby = new BoolSetting("Storage nearby",
        "Counts the containers a player brought within reach of each spawner and names them in the message.",
        true);
    private final RegistryListSetting<BlockEntityType<?>> storage = new RegistryListSetting<>("Storage",
        "Which containers count. Single chests and whatever the structures round a spawner make are always "
            + "left out. Minecarts and boats with chests count as well. Click to pick them.",
        BuiltInRegistries.BLOCK_ENTITY_TYPE, List.of(BlockEntityTypes.CHEST, BlockEntityTypes.TRAPPED_CHEST,
            BlockEntityTypes.BARREL, BlockEntityTypes.SHULKER_BOX, BlockEntityTypes.ENDER_CHEST,
            BlockEntityTypes.HOPPER, BlockEntityTypes.DROPPER, BlockEntityTypes.DISPENSER,
            BlockEntityTypes.CRAFTER)).under(storageNearby);
    private final BoolSetting onlyStorage = new BoolSetting("Only with storage",
        "Only reports and marks spawners with storage a player brought.", false).under(storageNearby);
    private final BoolSetting liveActivity = new BoolSetting("Live activity",
        "Tells you when another player stands at a spawner within 64 blocks of you or starts a trial spawner in "
            + "the chunks you have loaded.", true);
    private final BoolSetting minedSpawners = new BoolSetting("Mined spawners",
        "Also finds dungeons whose spawner a player mined and spider corridors with broken webs and no spawner.",
        true);
    private final BoolSetting intactRooms = new BoolSetting("Intact rooms too",
        "Also reports every dungeon and spider corridor nobody has mined.", false).under(minedSpawners);
    private final BoolSetting onlyChests = new BoolSetting("Only with chests",
        "Only reports dungeons that still hold a chest.", false).under(minedSpawners);
    private final Notice notice = new Notice(this, Notice.Where.CHAT);
    private final AlertSound alarm = new AlertSound("Rings when a spawner or room is found.",
        SoundEvents.BELL_BLOCK);
    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final ColorSetting spawnerColor = new ColorSetting("Spawner colour",
        "Colour of spawners players have been near.", 0, false);
    private final ColorSetting trialColor = new ColorSetting("Trial colour",
        "Colour of trial spawners players have fought.", 25, false);
    private final ColorSetting litColor = new ColorSetting("Lit colour",
        "Colour of spawners a player lit up.", 300, false).under(litSpawners);
    private final ColorSetting roomColor = new ColorSetting("Room colour",
        "Colour of dungeons and spider corridors.", 220, false).under(minedSpawners);
    private final BoolSetting wakeRange = new BoolSetting("Wake range",
        "Draws a ball round each marked spawner as far out as a player must come to wake it.", false);
    private final ColorSetting wakeColor = new ColorSetting("Wake range colour",
        "Colour of the ball round each spawner.", 195, false).under(wakeRange);
    private final FindLog finds = new FindLog(this);
    private final FindLines marks = new FindLines(finds, false);

    private final SpawnerLedger ledger = new SpawnerLedger();
    private final Watch watch = new Watch();
    private final ChunkScanner<Room> rooms = new ChunkScanner<Room>(2).borderReach(ROOM_BORDER);
    private final List<Survey> surveys = new ArrayList<>();
    // Finds kept at once whose message waits until their room has been read.
    private final Set<Long> untold = new HashSet<>();
    // What each room already told about showed.
    private final Map<Long, Evidence> toldRooms = new HashMap<>();
    // Live signs of a player at a spawner. Filled from the network thread.
    private final Queue<BlockPos> liveSigns = new ConcurrentLinkedQueue<>();
    private final Cooldowns<Long> liveQuiet = new Cooldowns<>();
    private final WorldWatch world = new WorldWatch();
    private int ticks;

    // The rooms the scanner holds by their key. A room find is drawn whole whilst it is loaded.
    private List<Room> roomsSeen = List.of();
    private final Long2ObjectMap<Room> roomsByKey = new Long2ObjectOpenHashMap<>();

    public SpawnerFinder() {
        super("SpawnerFinder", "Finds spawners players have been near and rooms whose spawner they mined.",
            Category.HUNTING);
        addSettings(places, brokenBlocks, litSpawners, storageNearby, storage, onlyStorage, liveActivity,
            minedSpawners, intactRooms, onlyChests);
        addSettings(notice.settings());
        addSettings(alarm.settings());
        addSettings(style.settings());
        addSettings(spawnerColor, trialColor, litColor, roomColor, wakeRange, wakeColor);
        addSettings(marks.settings());
        addSettings(FindsScreen.settingsFor(finds));
        searchTags("spawner", "activated spawner", "dungeon", "trial spawner", "stash", "spider corridor",
            "mineshaft");
        watch(watch);
    }

    private static List<String> placeLabels() {
        return Arrays.stream(Place.values()).map(Place::label).toList();
    }

    @Override
    public FindLog findLog() {
        return finds;
    }

    @Override
    public String getSuffix() {
        return count(visible().size());
    }

    @Override
    protected void onEnable() {
        forget();
    }

    @Override
    protected void onDisable() {
        forget();
        alarm.stop();
    }

    // A find whose room was still waiting stays kept without its message.
    private void forget() {
        surveys.clear();
        untold.clear();
        rooms.reset();
        toldRooms.clear();
        liveSigns.clear();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        rooms.markChanged(event.getPacket());
        if (!liveActivity.isOn()) {
            return;
        }
        switch (event.getPacket()) {
            case ClientboundBlockEventPacket blockEvent
                when blockEvent.getBlock() == Blocks.SPAWNER && blockEvent.getB0() == SPAWN_EVENT ->
                liveSigns.add(blockEvent.getPos());
            case ClientboundLevelEventPacket levelEvent
                when levelEvent.getType() == LevelEvent.PARTICLES_TRIAL_SPAWNER_DETECT_PLAYER
                || levelEvent.getType() == LevelEvent.PARTICLES_TRIAL_SPAWNER_DETECT_PLAYER_OMINOUS ->
                liveSigns.add(levelEvent.getPos());
            default -> {
            }
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (world.changed()) {
            forget();
        }
        ticks++;
        liveQuiet.tick();
        if (!ledger.ready()) {
            return;
        }
        judgeArrivals();
        BlockPos sign;
        while ((sign = liveSigns.poll()) != null) {
            liveSign(sign);
        }
        surveyWaiting();
        if (minedSpawners.isOn()) {
            rooms.update(mc.options.getEffectiveRenderDistance(), SpawnerRooms::scan);
            tellRooms();
        }
    }

    // Every reading that came in since the last look. The watch keeps them whilst this is off.
    private void judgeArrivals() {
        for (Map.Entry<Long, Arrival> entry : watch.loaded.entrySet()) {
            Arrival arrival = entry.getValue();
            if (arrival.judged || arrival.ownTicks < 0 && !watch.settled()) {
                continue;
            }
            arrival.judged = true;
            long key = entry.getKey();
            BlockPos pos = BlockPos.of(key);
            if (arrival.ownTicks < 0) {
                arrival.ownTicks = watch.ownTicks(pos, arrival.reach, TRAIL_TICKS);
            }
            SpawnerLedger.Entry prior = ledger.get(key);
            boolean near = arrival.ownTicks > 0 || arrival.ticksNear > 0 || prior != null && prior.near();
            ledger.read(key, arrival.reading, arrival.ticksNear, near);
            // A spawner you came near before any of its readings was kept tells nothing.
            Evidence evidence = prior != null && !prior.hasReading() ? null
                : arrival.trial ? judgeTrial(arrival, prior) : judgeSpawner(arrival, prior);
            if (evidence != null && arrival.live && liveActivity.isOn()) {
                // A change sent live is a player there now and is told at once.
                Survey survey = new Survey(pos, arrival, evidence, tellLive(key), ticks);
                survey(survey, watch.complete(survey));
            } else if (evidence != null) {
                if (keep(pos, new Kind(arrival.place, evidence, false), plainDetail(arrival))) {
                    untold.add(key);
                }
                surveys.add(new Survey(pos, arrival, evidence, false, ticks + SURVEY_TICKS));
            } else if (!near && !arrival.trial && brokenBlocks.isOn() && arrival.place.hasRoom()) {
                // Plain air round a spawner you never came near was left by someone else.
                surveys.add(new Survey(pos, arrival, null, false, ticks + SURVEY_TICKS));
            }
        }
    }

    // True when a spawner a player stands at may be told again. It then keeps quiet a while.
    private boolean tellLive(long key) {
        if (liveQuiet.contains(key)) {
            return false;
        }
        liveQuiet.put(key, LIVE_QUIET_TICKS);
        return true;
    }

    // Null for a spawner that shows nothing you could not have caused yourself.
    private static Evidence judgeSpawner(Arrival arrival, SpawnerLedger.Entry prior) {
        if (prior != null) {
            return arrival.reading != prior.reading() && !yoursSince(arrival, prior) ? Evidence.SPAWNER : null;
        }
        int untouched = arrival.place.untouchedCount();
        if (arrival.reading == untouched) {
            return null;
        }
        // Counting down takes a tick each. A new count after a spawn takes the whole count and one more.
        boolean countingDown = arrival.reading >= 0 && arrival.reading < untouched;
        int needed = countingDown ? untouched - arrival.reading : untouched + 1;
        return needed > arrival.ownTicks ? Evidence.SPAWNER : null;
    }

    // True when your own time near a spawner since its last reading explains its new count. A
    // count sent live changed just now. One that came with its chunk ran down by no more than
    // the ticks you stood within reach and only turned over to a new count if it reached nought.
    private static boolean yoursSince(Arrival arrival, SpawnerLedger.Entry prior) {
        if (arrival.live) {
            return arrival.ownTicks > 0;
        }
        int ran = prior.ticksNear() + arrival.ownTicks;
        if (ran == 0) {
            return false;
        }
        int slack = SLACK_TICKS + ServerInfo.pingTicks() + ran / STALL_PART;
        int lowest = prior.reading() - ran - slack;
        return lowest <= 0 || arrival.reading >= lowest && arrival.reading < prior.reading();
    }

    // A trial spawner shows a fight in its state. One already fought when last read tells nothing new.
    private static Evidence judgeTrial(Arrival arrival, SpawnerLedger.Entry prior) {
        boolean known = prior != null && (yours(arrival, prior) || fought(prior.reading()));
        return fought(arrival.reading) && arrival.ownTicks == 0 && !known ? Evidence.SPAWNER : null;
    }

    // A change sent live happened just now and only your last second near it could explain it.
    // One that came with its chunk may date from any time you stood near it since the last reading.
    private static boolean yours(Arrival arrival, SpawnerLedger.Entry prior) {
        return arrival.ownTicks > 0 || !arrival.live && prior.ticksNear() > 0;
    }

    private static int trialReading(BlockState state) {
        return state.getValue(TrialSpawnerBlock.STATE).ordinal() * 2
            + (state.getValue(TrialSpawnerBlock.OMINOUS) ? 1 : 0);
    }

    private static boolean ominous(int reading) {
        return reading % 2 == 1;
    }

    // A count kept whilst a mob spawner stood at the spot is no trial state.
    private static boolean fought(int reading) {
        int state = reading / 2;
        return state >= 0 && state < TRIAL_STATES.length
            && (ominous(reading) || FOUGHT.contains(TRIAL_STATES[state]));
    }

    // A spawn cycle or a detected player at a spawner whose reach you are outside of.
    private void liveSign(BlockPos pos) {
        Arrival arrival = watch.arrivalAt(pos);
        if (arrival == null || watch.ticksWithin(pos, arrival.reach + REACH_SLACK, LIVE_TICKS) > 0
            || !tellLive(pos.asLong())) {
            return;
        }
        Survey survey = new Survey(pos.immutable(), arrival, Evidence.SPAWNER, true, ticks);
        survey(survey, watch.complete(survey));
    }

    // Reads the room of each waiting find once its neighbours and their entities are in.
    private void surveyWaiting() {
        for (Survey survey : List.copyOf(surveys)) {
            if (!watch.complete(survey)) {
                survey.readyAt = -1;
                boolean gone = !mc.level.hasChunk(survey.pos.getX() >> 4, survey.pos.getZ() >> 4);
                // An untouched spawner waits for as long as it is loaded.
                if (gone && survey.evidence == null) {
                    surveys.remove(survey);
                } else if (gone || survey.evidence != null && ticks >= survey.deadline) {
                    survey(survey, false);
                }
                continue;
            }
            if (survey.readyAt < 0) {
                survey.readyAt = ticks + SETTLE_TICKS;
            }
            if (ticks >= survey.readyAt) {
                survey(survey, true);
            }
        }
    }

    // Works out what a find shows and says about its room and then keeps it.
    private void survey(Survey survey, boolean complete) {
        surveys.remove(survey);
        Arrival arrival = survey.arrival;
        ChunkWindow world = ChunkWindow.live();
        Evidence evidence = survey.evidence;
        boolean traced = false;
        if (evidence == null) {
            if (!brokenBlocks.isOn() || !complete) {
                return;
            }
            Room room = SpawnerRooms.around(arrival.place, world::get, survey.pos);
            // You may have come near or broken a block there whilst the room waited.
            if (room == null || !room.disturbed() || yourWork(room, false)) {
                return;
            }
            evidence = Evidence.SPAWNER;
            traced = true;
        }
        String lights = "";
        if (litSpawners.isOn() && !arrival.trial && SpawnerRooms.lightShy(arrival.mob)) {
            Tally lit = playerLights(world, survey.pos, arrival);
            if (!lit.isEmpty()) {
                evidence = Evidence.LIT;
                lights = " lit by " + lit.words();
            }
        }
        Tally stored = storageNearby.isOn() ? storageNear(survey.pos, arrival) : new Tally();
        String stash = stored.isEmpty() ? "" : " with " + stored.words() + " nearby";
        String name = spawnerName(arrival);
        String detail = (traced ? ChatUtil.withArticle(name) + " where a player broke a block" : plainDetail(arrival))
            + lights;
        String message = survey.live ? "a player at " + ChatUtil.withArticle(name) + stash : detail + stash;
        found(survey.pos, new Kind(arrival.place, evidence, !stored.isEmpty()), detail + stash, message,
            survey.live);
    }

    // True for a room that may hold your own work. You broke a block inside it this session or
    // the ledger holds a spot inside it you came near. For a room with its spawner gone only a
    // visit since that spawner's last reading counts. A dig this session is kept for good.
    private boolean yourWork(Room room, boolean sinceReading) {
        if (OwnDigs.anyIn(room.box())) {
            ledger.cameNear(room.key().asLong());
            return true;
        }
        for (BlockPos cell : BlockPos.betweenClosed(room.box().contract(1, 1, 1))) {
            SpawnerLedger.Entry entry = ledger.get(cell.asLong());
            if (entry != null && (sinceReading ? entry.ticksNear() > 0 : entry.near())) {
                return true;
            }
        }
        return false;
    }

    // What a spawner showed before its room is read such as an activated zombie spawner in a dungeon.
    private static String plainDetail(Arrival arrival) {
        String name = spawnerName(arrival);
        if (arrival.trial) {
            return "a " + name + (ominous(arrival.reading) ? " a player turned ominous" : " players have fought");
        }
        return "an activated " + name;
    }

    // Such as zombie spawner in a dungeon or trial spawner.
    private static String spawnerName(Arrival arrival) {
        if (arrival.trial) {
            return "trial spawner";
        }
        String mob = SpawnerMob.name(arrival.mob);
        return (mob.isEmpty() ? "mob" : mob) + " spawner " + arrival.place.where();
    }

    // The lights in a spawner's room that no cave or structure there makes such as 3 torches.
    private static Tally playerLights(ChunkWindow world, BlockPos pos, Arrival arrival) {
        Tally lights = new Tally();
        int reach = arrival.spawnRange;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -LIGHT_BELOW; dy <= LIGHT_ABOVE; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    BlockPos spot = pos.offset(dx, dy, dz);
                    BlockState state = world.get(spot);
                    if (state.getLightEmission() > 0 && !arrival.place.makesLight(world::get, spot, state)) {
                        lights.add(ChatUtil.words(state.getBlock()));
                    }
                }
            }
        }
        return lights;
    }

    // The containers within reach of a spawner that no structure there would make.
    private Tally storageNear(BlockPos pos, Arrival arrival) {
        Tally stored = new Tally();
        int reach = arrival.reach;
        double reachSqr = (double) reach * reach;
        for (int cx = (pos.getX() - reach) >> 4; cx <= (pos.getX() + reach) >> 4; cx++) {
            for (int cz = (pos.getZ() - reach) >> 4; cz <= (pos.getZ() + reach) >> 4; cz++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (storage.contains(blockEntity.getType()) && blockEntity.getBlockPos().distSqr(pos) <= reachSqr
                        && !arrival.place.makes(blockEntity.getType(), doubleChest(blockEntity.getBlockState()))) {
                        stored.add(containerName(blockEntity.getType()));
                    }
                }
            }
        }
        Vec3 centre = Vec3.atCenterOf(pos);
        for (Entity entity : mc.level.getEntitiesOfClass(Entity.class, new AABB(pos).inflate(reach),
            entity -> entity instanceof ContainerEntity)) {
            if (entity.distanceToSqr(centre) <= reachSqr && !arrival.place.makesCart(entity.getType())) {
                stored.add(cartName(entity));
            }
        }
        return stored;
    }

    private static boolean doubleChest(BlockState state) {
        return state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
    }

    private static String containerName(BlockEntityType<?> type) {
        return ChatUtil.words(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type));
    }

    // Carts and boats are counted under plain words such as 2 chest minecarts.
    private static String cartName(Entity entity) {
        return switch (entity) {
            case MinecartHopper _ -> "hopper minecart";
            case AbstractChestBoat _ -> "chest boat";
            default -> "chest minecart";
        };
    }

    // Tells each room the scanner turned up once for what it shows. The spawner you mined or
    // the webs you cut leave a room that looks mined and it stays quiet.
    private void tellRooms() {
        for (Room room : rooms.results()) {
            Evidence evidence = room.mined() ? Evidence.MINED : Evidence.ROOM;
            long key = room.key().asLong();
            if (evidence == Evidence.ROOM && !intactRooms.isOn() || toldRooms.get(key) == evidence) {
                continue;
            }
            toldRooms.put(key, evidence);
            if (evidence == Evidence.MINED && yourWork(room, true)) {
                continue;
            }
            boolean dungeon = room.place() == Place.DUNGEON;
            String what = dungeon ? "a dungeon" : "a spider corridor";
            if (evidence == Evidence.MINED) {
                what += dungeon ? " whose spawner a player mined" : " with broken webs and no spawner";
            }
            if (room.chests() > 0) {
                what += " with " + Tally.counted(room.chests(), "chest");
            }
            found(room.key(), new Kind(room.place(), evidence, room.chests() > 0), what, what, false);
        }
    }

    // Keeps a find and tells you about it when it is new or shows something new. A live sign
    // is told whenever it comes. A find you took away by hand stays away.
    private void found(BlockPos pos, Kind kind, String detail, String message, boolean live) {
        FindLog.Find old = finds.at(pos);
        Kind before = old == null ? null : Kind.parse(old.kind());
        if (outranked(before, kind) || !finds.add(pos, kind.name(), detail) && old == null) {
            return;
        }
        boolean first = untold.remove(pos.asLong()) || old == null;
        if ((first || live || !kind.equals(before)) && shows(kind)) {
            notice.tell(kind.name(), message, pos);
            if (first || live) {
                alarm.ring();
            }
        }
    }

    // Keeps a find without a word. False when a find of higher rank holds the spot or you took
    // the find away by hand.
    private boolean keep(BlockPos pos, Kind kind, String detail) {
        FindLog.Find old = finds.at(pos);
        if (outranked(old == null ? null : Kind.parse(old.kind()), kind)) {
            return false;
        }
        return finds.add(pos, kind.name(), detail) || old != null;
    }

    private static boolean outranked(Kind before, Kind kind) {
        return before != null && before.evidence().rank > kind.evidence().rank;
    }

    private boolean shows(Kind kind) {
        if (!places.contains(kind.place().label())) {
            return false;
        }
        if (kind.evidence().ofSpawner()) {
            return !storageNearby.isOn() || !onlyStorage.isOn() || kind.marked();
        }
        if (!minedSpawners.isOn() || kind.evidence() == Evidence.ROOM && !intactRooms.isOn()) {
            return false;
        }
        return !onlyChests.isOn() || kind.place() != Place.DUNGEON || kind.marked();
    }

    // The finds in this dimension the filters let through.
    private List<FindLog.Find> visible() {
        if (mc.level == null) {
            return List.of();
        }
        List<Object> filters = List.of(Set.copyOf(places.getValue()), storageNearby.isOn(), onlyStorage.isOn(),
            minedSpawners.isOn(), intactRooms.isOn(), onlyChests.isOn());
        return finds.filtered(filters, find -> {
            Kind kind = Kind.parse(find.kind());
            return kind != null && shows(kind);
        });
    }

    private int colorOf(Kind kind) {
        return switch (kind.evidence()) {
            case ROOM, MINED -> roomColor.getColor();
            case LIT -> litColor.getColor();
            case SPAWNER -> kind.place() == Place.TRIAL_CHAMBER ? trialColor.getColor() : spawnerColor.getColor();
        };
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        List<FindLog.Find> visible = visible();
        refreshRoomBoxes();
        for (FindLog.Find find : visible) {
            if (!marks.inDrawRange(find)) {
                continue;
            }
            Kind kind = Kind.parse(find.kind());
            Room room = kind.evidence().ofSpawner() ? null : roomsByKey.get(find.pos().asLong());
            AABB box = room == null ? DrawBatch.blockBox(find.pos()) : room.box();
            style.draw(batch, box, colorOf(kind), true);
            if (wakeRange.isOn() && kind.evidence().ofSpawner()) {
                drawRange(batch, find.pos());
            }
        }
        marks.draw(batch, visible, find -> colorOf(Kind.parse(find.kind())));
    }

    private void refreshRoomBoxes() {
        List<Room> now = rooms.results();
        if (now == roomsSeen) {
            return;
        }
        roomsSeen = now;
        roomsByKey.clear();
        for (Room room : now) {
            roomsByKey.put(room.key().asLong(), room);
        }
    }

    // The reach of a loaded spawner as a wire ball. The server sends each spawner's own reach.
    private void drawRange(DrawBatch batch, BlockPos pos) {
        Arrival arrival = watch.arrivalAt(pos);
        if (arrival != null) {
            batch.sphere(Vec3.atCenterOf(pos), arrival.reach, wakeColor.getColor(), true);
        }
    }

    // Keeps the readings of every loaded spawner whether or not the module is on. A spawner
    // read whilst this is off is judged once it comes on. Every tick near one is always counted.
    private final class Watch {

        private final Map<Long, Arrival> loaded = new HashMap<>();
        // Spawners the server sent new data for. Filled from the network thread.
        private final Queue<BlockPos> changed = new ConcurrentLinkedQueue<>();
        // Changes seen last tick. The game applies a packet after the watch sees it.
        private List<BlockPos> due = new ArrayList<>();
        private final WorldWatch world = new WorldWatch();
        private final Vec3[] trail = new Vec3[TRAIL_TICKS];
        private int trailNext;
        private int trailSize;

        @Subscribe
        private void onChunkData(ChunkDataEvent event) {
            followWorld();
            for (BlockEntity blockEntity : event.getChunk().getBlockEntities().values()) {
                arrive(blockEntity, false);
            }
        }

        // A crowded chunk on Paper sends its spawners after it. A trial spawner sends its state
        // when a fight starts or ends.
        @Subscribe
        private void onPacketReceive(PacketReceiveEvent event) {
            switch (event.getPacket()) {
                case ClientboundBlockEntityDataPacket data when data.getType() == BlockEntityTypes.MOB_SPAWNER
                    || data.getType() == BlockEntityTypes.TRIAL_SPAWNER -> changed.add(data.getPos());
                case ClientboundBlockUpdatePacket update when update.getBlockState().is(Blocks.TRIAL_SPAWNER) ->
                    changed.add(update.getPos());
                case ClientboundSectionBlocksUpdatePacket section -> section.runUpdates((pos, state) -> {
                    if (state.is(Blocks.TRIAL_SPAWNER)) {
                        changed.add(pos.immutable());
                    }
                });
                default -> {
                }
            }
        }

        @Subscribe
        private void onTick(TickEvent event) {
            if (!inGame()) {
                return;
            }
            followWorld();
            trail[trailNext] = mc.player.position();
            trailNext = (trailNext + 1) % TRAIL_TICKS;
            trailSize = Math.min(trailSize + 1, TRAIL_TICKS);
            List<BlockPos> applied = due;
            due = new ArrayList<>();
            BlockPos pos;
            while ((pos = changed.poll()) != null) {
                due.add(pos);
            }
            for (BlockPos spot : applied) {
                BlockEntity blockEntity = mc.level.getBlockEntity(spot);
                if (blockEntity != null) {
                    arrive(blockEntity, true);
                }
            }
            sweep();
        }

        // The first chunks of a new world can come before its first tick.
        private void followWorld() {
            if (world.changed()) {
                loaded.clear();
                changed.clear();
                due = new ArrayList<>();
                trailSize = 0;
            }
        }

        private void arrive(BlockEntity blockEntity, boolean sent) {
            long key = blockEntity.getBlockPos().asLong();
            Arrival arrival = arrivalOf(blockEntity, sent && loaded.containsKey(key));
            if (arrival != null) {
                loaded.put(key, arrival);
            }
        }

        // Null for a block entity that is no spawner.
        private Arrival arrivalOf(BlockEntity blockEntity, boolean live) {
            BlockPos pos = blockEntity.getBlockPos();
            Arrival arrival;
            if (blockEntity instanceof SpawnerBlockEntity spawner) {
                BaseSpawner base = spawner.getSpawner();
                EntityType<?> mob = SpawnerMob.of(spawner);
                Place place = Place.of(mob, mc.level.dimension(), mc.level.getBlockState(pos.below()));
                arrival = new Arrival(false, base.spawnDelay, base.requiredPlayerRange, base.spawnRange, place,
                    mob, live);
            } else if (blockEntity instanceof TrialSpawnerBlockEntity trial
                && mc.level.getBlockState(pos).is(Blocks.TRIAL_SPAWNER)) {
                arrival = new Arrival(true, trialReading(mc.level.getBlockState(pos)),
                    trial.getTrialSpawner().getRequiredPlayerRange(), 0, Place.TRIAL_CHAMBER, null, live);
            } else {
                return null;
            }
            arrival.ownTicks = settled() ? ownTicks(pos, arrival.reach, live ? LIVE_TICKS : TRAIL_TICKS) : -1;
            return arrival;
        }

        // The arrival of a loaded spawner or one read now for a spawner never seen. Null when
        // no spawner stands there.
        private Arrival arrivalAt(BlockPos pos) {
            Arrival arrival = loaded.get(pos.asLong());
            if (arrival != null) {
                return arrival;
            }
            BlockEntity blockEntity = mc.level.getBlockEntity(pos);
            return blockEntity == null ? null : arrivalOf(blockEntity, true);
        }

        // Drops spawners gone with their chunk or broken. Each tick you stand within reach of one
        // counts for it and goes in the ledger.
        private void sweep() {
            Vec3 you = mc.player.position();
            for (Iterator<Map.Entry<Long, Arrival>> it = loaded.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Long, Arrival> entry = it.next();
                BlockPos pos = BlockPos.of(entry.getKey());
                BlockEntity blockEntity = mc.level.getBlockEntity(pos);
                Arrival arrival = entry.getValue();
                double reach = arrival.reach + REACH_SLACK;
                if (!(blockEntity instanceof SpawnerBlockEntity) && !(blockEntity instanceof TrialSpawnerBlockEntity)) {
                    // A spawner broken whilst its chunk stays loaded and you stand away is someone else's work.
                    if (mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) && ticksWithin(pos, reach, LIVE_TICKS) == 0
                        && !OwnDigs.anyIn(new AABB(pos))) {
                        ledger.takenByOthers(entry.getKey());
                    }
                    it.remove();
                    continue;
                }
                if (you.distanceToSqr(Vec3.atCenterOf(pos)) < reach * reach) {
                    arrival.ticksNear++;
                    ledger.cameNear(entry.getKey());
                }
            }
        }

        // True once your spot in this world is known.
        private boolean settled() {
            return trailSize >= SPOT_KNOWN_TICKS;
        }

        // How long you stood within reach of a spawner lately. A spell near it gains the ticks the
        // server counted before your spot reached this side.
        private int ownTicks(BlockPos pos, int reach, int back) {
            int within = ticksWithin(pos, reach + REACH_SLACK, back);
            return within == 0 ? 0 : within + SLACK_TICKS + ServerInfo.pingTicks();
        }

        // How many of the last ticks you stood within reach of a block.
        private int ticksWithin(BlockPos pos, double reach, int back) {
            Vec3 centre = Vec3.atCenterOf(pos);
            int count = 0;
            for (int i = 1; i <= Math.min(back, trailSize); i++) {
                if (trail[Math.floorMod(trailNext - i, TRAIL_TICKS)].distanceToSqr(centre) < reach * reach) {
                    count++;
                }
            }
            return count;
        }

        // True once every chunk a find's room and storage reach into has arrived.
        private boolean complete(Survey survey) {
            int reach = Math.max(survey.arrival.reach, ROOM_REACH);
            return ChunkWindow.capture(survey.pos, (reach + 15) >> 4).complete();
        }
    }
}
