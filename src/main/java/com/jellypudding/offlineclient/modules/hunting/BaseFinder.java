package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindSource;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.gui.FindsScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.FindLines;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryCountSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.AlertSound;
import com.jellypudding.offlineclient.util.BaseClue;
import com.jellypudding.offlineclient.util.BlockClues;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChunkPaint;
import com.jellypudding.offlineclient.util.ChunkScanner;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityClues;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.NearestCut;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.OwnSimulation;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.ServerWatch;
import com.jellypudding.offlineclient.util.SignWords;
import com.jellypudding.offlineclient.util.SpawnerMob;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.TextCut;
import com.jellypudding.offlineclient.util.Villages;
import com.jellypudding.offlineclient.util.WorldWatch;
import com.jellypudding.offlineclient.worldgen.BaseBlocks;
import com.jellypudding.offlineclient.worldgen.NaturalBlocks;
import com.jellypudding.offlineclient.worldgen.PlacedOnly;
import com.jellypudding.offlineclient.worldgen.StructureBeds;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

// Marks the chunks around you that show a player built or lived there. Each clue was checked
// against the structure templates and world generation of 26.3. Nothing the world makes by
// itself marks a chunk with the settings as they start.
public final class BaseFinder extends Module implements FindSource, ChunkPaint {

    // What the glow lights up.
    private enum Glow { OFF, NEVER_NATURAL, PLACED }

    // What one chunk holds of a kind of entity and where the first of them stood.
    private static final class ChunkTally {

        private final BlockPos pos;
        // Words for the first such as cobblestone.
        private final String first;
        private int count;
        private int entities;
        // The heights of the entities added up. A label floats over their middle.
        private double heights;

        private ChunkTally(Entity entity, String first) {
            pos = entity.blockPosition();
            this.first = first;
        }

        private void add(Entity entity, int amount) {
            count += amount;
            entities++;
            heights += entity.getY();
        }

        private double middleY() {
            return heights / entities;
        }
    }

    // An outpost tower on the highest peak ends at 276. Nothing else the world builds reaches it.
    private static final int SKY_HEIGHT = 277;

    // The world spawns at most eight creatures in a pack and a further pack one time in ten. A
    // chunk only holds this many after three full packs.
    private static final int CROWD_SIZE = 24;

    // A base spans several chunks and they load together.
    private static final int QUIET_SECONDS = 5;

    // Twice a second is quick enough for mobs that wander and boats left by a shore. Beds waiting
    // on the ground around them are judged as often.
    private static final int ENTITY_SCAN_TICKS = 10;

    // The writing quoted from a sign stops after this many characters.
    private static final int SIGN_QUOTE = 40;

    private static final int GROWTH_COUNT = 3;

    // Well under the sea where nearly every dropped block lies in a cave or a tunnel.
    private static final int DROP_HEIGHT = 50;
    // Mined blocks lie in groups. Four together rarely come from anything else.
    private static final int DROP_COUNT = 4;
    // An item first seen this close came from your own digging or your own hand. A thrown stack
    // flies a few blocks in the half second between two looks.
    private static final double OWN_DROP_REACH = 10;
    // Item ids judged yours or not. Enough for every item in reach many times over.
    private static final int KEPT_DROPS = 4096;

    // The crowd count floats this far over the middle of the mobs.
    private static final double LABEL_LIFT = 2;
    private static final float LABEL_SCALE = 1;

    // Hues of the three colours.
    private static final float ORANGE = 30;
    private static final float SKY_BLUE = 195;
    private static final float GREEN = 120;

    private static final int GLOW_ALPHA = 64;

    private static final String JOIN_FIRST = "Join a world first";

    private final BoolSetting signs = new BoolSetting("Written signs",
        "Marks chunks with a sign that has writing on it. The arrows on igloo signs never count.", true);
    private final BoolSetting portals = new BoolSetting("Lit portals",
        "Marks chunks with a lit nether portal or an end portal outside the End. The world lights neither.", true);
    private final BoolSetting bubbles = new BoolSetting("Soul sand bubbles",
        "Marks chunks with a bubble column rising from soul sand. The world keeps soul fire on its soul sand.",
        true);
    private final BoolSetting sky = new BoolSetting("Sky builds",
        "Marks overworld chunks with blocks above the sky height that trees and snow and endermen never "
            + "leave there.", true);
    private final NumberSetting skyHeight = new NumberSetting("Sky height",
        "Blocks from this height up count. Normal ground ends at 255 and an outpost on the highest peak at 276.",
        SKY_HEIGHT, 200, 320, 1).min(DimensionType.MIN_Y).max(DimensionType.MAX_Y).under(sky);
    private final BoolSetting bedrock = new BoolSetting("Placed bedrock",
        "Marks chunks with bedrock above the floor layers or away from the nether roof. The End makes its own "
            + "and is left out.", true);
    private final BoolSetting roof = new BoolSetting("Nether roof",
        "Marks nether chunks with anything but mushrooms on top of the bedrock roof.", true);
    private final BoolSetting spawners = new BoolSetting("Lone spawners",
        "Marks chunks with a spawner that has none of the blocks of its dungeon or structure around it. "
            + "Players strip them to build mob farms.", true);
    private final BoolSetting beds = new BoolSetting("Lone beds",
        "Marks chunks with a bed that no village or igloo or camp or trial chamber put there. A bed in a village "
            + "colour is only judged when you stand within a few chunks of it and never below a render distance "
            + "of five.", true);
    private final BoolSetting rooms = new BoolSetting("Bedrock rooms",
        "Marks chunks with a hollow two blocks tall sealed inside the bedrock layers. The world makes none. On "
            + "Paper the hollow must also hold a chest or sign or other block that anti xray never fakes.", true);
    private final BoolSetting sideways = new BoolSetting("Sideways deepslate",
        "Marks chunks with deepslate laid on its side. The world lays it upright everywhere but in ancient city "
            + "pillars where deepslate tile stairs stand beside it.", true);
    private final BoolSetting growth = new BoolSetting("Long growth",
        "Marks chunks with kelp fourteen blocks tall reaching the surface or vines hanging eight blocks free or "
            + "dripstone longer than the world makes. Plants only grow that far near players for a long time. Chunks "
            + "you came near are never judged.",
        true);
    private final NumberSetting growthCount = new NumberSetting("Growth count",
        "How many grown plants one chunk must hold.", GROWTH_COUNT, 1, 20, 1).under(growth);
    private final BoolSetting playerBlocks = new BoolSetting("Player blocks",
        "Marks chunks that hold enough of the blocks on the list.", true);
    private final RegistryCountSetting<Block> blockCounts = new RegistryCountSetting<>("Blocks",
        "Each number is how many of that block alone mark a chunk. Different blocks add up. Click to pick "
            + "them and click a number to change it.", BuiltInRegistries.BLOCK, BaseBlocks.NEEDED, block -> 1, 1)
        .under(playerBlocks);

    private final NumberSetting floorMargin = new NumberSetting("Floor margin",
        "Block clues leave out this many layers at the bottom of the world. Raise it on a server that puts "
            + "its own blocks down there.", 0, 0, 64, 1, " layers");
    private final NumberSetting ceilingMargin = new NumberSetting("Ceiling margin",
        "Block clues leave out this many layers at the top of the world. Raise it on a server that puts "
            + "its own blocks up there.", 0, 0, 64, 1, " layers");

    private final BoolSetting decorations = new BoolSetting("Decorations",
        "Marks chunks with item frames or paintings or dressed armour stands. End ship frames and village "
            + "armour stands never count.", true);
    private final BoolSetting pearls = new BoolSetting("Stasis pearls",
        "Marks chunks with an ender pearl held up in a bubble column. Your own pearls never count.", true);
    private final BoolSetting named = new BoolSetting("Named mobs",
        "Marks chunks with a mob wearing a name. The world names none.", true);
    private final BoolSetting villagers = new BoolSetting("Traded villagers",
        "Marks chunks with a villager past its first level. Only trading raises it.", true);
    private final BoolSetting boats = new BoolSetting("Boats",
        "Marks chunks with a boat or raft that nobody rides. The world places none. Your own boats never count.",
        true);
    private final BoolSetting lostMobs = new BoolSetting("Lost mobs",
        "Marks chunks with a mob in a dimension it cannot spawn in such as a cow in the nether. Mobs also wander "
            + "through the portals players light.", false);
    private final BoolSetting pets = new BoolSetting("Pets and mounts",
        "Marks chunks with a tamed or saddled or leashed mob or one carrying a chest that is not yours. "
            + "Skeleton trap horses and strider jockeys and trader llamas never count.", true);
    private final BoolSetting crowds = new BoolSetting("Crowds",
        "Marks chunks crowded with the mobs below. The server only shows mobs within about a hundred blocks.",
        true);
    private final NumberSetting crowdSize = new NumberSetting("Crowd size",
        "How many of those mobs one chunk must hold. The world spawns at most eight in a pack and a further "
            + "pack one time in ten.", CROWD_SIZE, 4, 100, 1).min(1).under(crowds);
    private final RegistryListSetting<EntityType<?>> crowdMobs = new RegistryListSetting<>("Crowd mobs",
        "The mobs a crowd is counted from. Click to pick them.", BuiltInRegistries.ENTITY_TYPE, creatures())
        .under(crowds);
    private final BoolSetting crowdCounts = new BoolSetting("Crowd counts",
        "Writes over each nearby chunk how many crowd mobs it holds.", false).under(crowds);
    private final BoolSetting drops = new BoolSetting("Dropped blocks",
        "Marks chunks where mined blocks lie dropped underground. Drops first seen near you never count.", true);
    private final NumberSetting dropCount = new NumberSetting("Drop count",
        "How many dropped blocks one chunk must hold.", DROP_COUNT, 2, 32, 1).min(1).under(drops);
    private final NumberSetting dropHeight = new NumberSetting("Drop height",
        "Only drops at or below this height count.", DROP_HEIGHT, -64, 320, 1)
        .min(DimensionType.MIN_Y).max(DimensionType.MAX_Y).under(drops);

    private final FindLog finds = FindLog.byChunk(this);
    private final Notice notice = new Notice(this, Notice.Where.CHAT, QUIET_SECONDS);
    private final AlertSound alarm = new AlertSound("Rings when a chunk is marked.", SoundEvents.BEACON_ACTIVATE);
    private final ColorSetting blockColour = new ColorSetting("Block colour",
        "Colour of the boxes and lines and columns of chunks marked by their blocks.", ORANGE, false);
    private final ColorSetting entityColour = new ColorSetting("Entity colour",
        "Colour of the boxes and lines and columns of chunks marked by mobs and boats and frames and drops.",
        SKY_BLUE, false);
    private final ColorSetting markedColour = new ColorSetting("Marked colour",
        "Colour of the boxes and lines and columns of chunks you marked by hand.", GREEN, false);
    private final BoxStyle box = BoxStyle.shapeOnly("Box", BoxStyle.Shape.LINES);
    private final FindLines marks = new FindLines(finds, FindLines.Tracers.NEAREST, true);

    private final EnumSetting<Glow> glow = new EnumSetting<>("Glow",
        "Lights up the single blocks near you that players put down.", Glow.NEVER_NATURAL)
        .describe(Glow.OFF, "No block lights up.")
        .describe(Glow.NEVER_NATURAL, "Blocks the world never makes such as concrete and shulker boxes.")
        .describe(Glow.PLACED, "Anything that is not natural terrain. Villages and mineshafts light up too.");
    private final NumberSetting glowRange = new NumberSetting("Glow range",
        "How many chunks out from your own light up.", 4, 1, 12, 1, " chunks")
        .min(0).max(ChunkMap.MAX_VIEW_DISTANCE).under(glow, Glow.NEVER_NATURAL, Glow.PLACED);
    private final NumberSetting glowLimit = new NumberSetting("Glow limit",
        "The most blocks lit up at once with the nearest first.", 2000, 100, 10000, 100)
        .min(1).under(glow, Glow.NEVER_NATURAL, Glow.PLACED);
    private final ColorSetting glowColour = new ColorSetting("Glow colour", "Colour of the blocks lit up.", 0, false)
        .under(glow, Glow.NEVER_NATURAL, Glow.PLACED);

    private final ActionSetting nearest = new ActionSetting("Nearest base",
        "Names the nearest marked chunk in chat. A click on its coordinates walks there.", this::nearestBase);
    private final ActionSetting markHere = new ActionSetting("Mark here",
        "Marks the chunk you stand in by hand.", this::markHere);
    private final ActionSetting unmarkHere = new ActionSetting("Unmark here",
        "Forgets the mark on the chunk you stand in. Its clues mark it again once you switch this off and on.",
        this::unmarkHere);
    private final ActionSetting unmarkNewest = new ActionSetting("Unmark newest",
        "Forgets the newest mark in the dimension you are in.", this::unmarkNewest);

    private final Map<BaseClue, BoolSetting> blockToggles = Map.ofEntries(Map.entry(BaseClue.SIGN, signs),
        Map.entry(BaseClue.PORTAL, portals), Map.entry(BaseClue.BUBBLES, bubbles), Map.entry(BaseClue.SKY, sky),
        Map.entry(BaseClue.BEDROCK, bedrock), Map.entry(BaseClue.ROOF, roof), Map.entry(BaseClue.SPAWNER, spawners),
        Map.entry(BaseClue.BED, beds), Map.entry(BaseClue.ROOM, rooms), Map.entry(BaseClue.SIDEWAYS, sideways),
        Map.entry(BaseClue.GROWTH, growth));
    private final Map<BaseClue, BoolSetting> entityToggles = Map.of(BaseClue.DECORATION, decorations,
        BaseClue.PEARL, pearls, BaseClue.NAMED, named, BaseClue.VILLAGER, villagers, BaseClue.BOAT, boats,
        BaseClue.PET, pets, BaseClue.LOST_MOB, lostMobs);

    private final ChunkScanner<BlockClues.Hit> scanner = new ChunkScanner<BlockClues.Hit>().onResult(this::landed);
    private final ChunkScanner<BlockPos> glowScanner = new ChunkScanner<>(0);
    private final NearestCut<BlockPos> lit = new NearestCut<>(BlockPos::distToCenterSqr);
    // The chunks judged in each world since this was switched on. A mark taken away stays away.
    private final Map<String, LongSet> judged = new HashMap<>();
    // Beds of a village colour waiting for every chunk a village could reach them from. Keyed by
    // the chunk of the bed.
    private final Map<Long, BlockClues.Hit> waitingBeds = new HashMap<>();
    // Whether each item was yours when it was first seen. Item ids never repeat whilst a server
    // runs and the verdicts outlast a toggle and a trip through a portal.
    private final Map<Integer, Boolean> ownDrops = new BoundedMap<>(KEPT_DROPS);
    private final ServerWatch server = new ServerWatch();
    private final WorldWatch world = new WorldWatch();
    // The crowd mobs in each chunk at the last look.
    private Map<Long, ChunkTally> crowded = Map.of();
    // The settings the chunks in the scanner were read by.
    private BlockClues.Rules rules;
    private Glow glowMode;
    private int entityTicks;

    public BaseFinder() {
        super("BaseFinder", "Marks the chunks around you where players built or lived.", Category.HUNTING);
        addSettings(signs, portals, bubbles, sky, skyHeight, bedrock, roof, spawners, beds, rooms, sideways, growth,
            growthCount, playerBlocks, blockCounts, floorMargin, ceilingMargin);
        addSettings(decorations, pearls, named, villagers, boats, pets, lostMobs, crowds, crowdSize, crowdMobs,
            crowdCounts, drops, dropCount, dropHeight);
        addSettings(notice.settings());
        addSettings(alarm.settings());
        addSettings(blockColour, entityColour, markedColour);
        addSettings(box.settings());
        addSettings(marks.settings());
        addSettings(glow, glowRange, glowLimit, glowColour);
        addSettings(nearest, markHere, unmarkHere, unmarkNewest);
        addSettings(FindsScreen.settingsFor(finds));
        searchTags("base finder", "bases", "builds", "sky build", "nether roof", "portal", "stasis",
            "item frame", "villager", "boat", "spawner", "bedrock", "bubble column", "sign", "man made",
            "player blocks", "factions", "bed", "stash", "mining", "dropped items", "deepslate", "kelp", "vines",
            "dripstone");
    }

    // Every mob the game counts as a creature such as cows and sheep and horses.
    private static List<EntityType<?>> creatures() {
        List<EntityType<?>> types = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() == MobCategory.CREATURE) {
                types.add(type);
            }
        }
        return types;
    }

    @Override
    public FindLog findLog() {
        return finds;
    }

    // The map paints each chunk with a clue in the colour of where the clue came from.
    @Override
    public void paintChunks(Long2IntMap colours) {
        for (FindLog.Find find : finds.here()) {
            colours.put(ChunkPos.pack(find.pos()), colour(find));
        }
    }

    @Override
    public String getSuffix() {
        return count(finds.count());
    }

    @Override
    protected void onEnable() {
        judged.clear();
        rules = null;
        glowMode = null;
        entityTicks = 0;
    }

    @Override
    protected void onDisable() {
        alarm.stop();
        scanner.reset();
        glowScanner.reset();
        lit.clear();
        waitingBeds.clear();
        crowded = Map.of();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        scanner.markChanged(event.getPacket());
        glowScanner.markChanged(event.getPacket());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (server.changed()) {
            ownDrops.clear();
        }
        if (world.changed()) {
            waitingBeds.clear();
            crowded = Map.of();
        }
        BlockClues.Rules now = currentRules();
        if (!now.equals(rules)) {
            // A changed setting reads every chunk again. Chunks already marked stay quiet.
            rules = now;
            scanner.reset();
            waitingBeds.clear();
        }
        scanner.update(ChunkScanner.heldRadius(), (view, out) -> BlockClues.scan(now, view, out));
        if (++entityTicks >= ENTITY_SCAN_TICKS) {
            entityTicks = 0;
            scanEntities();
            judgeWaitingBeds();
        }
        updateGlow();
    }

    private BlockClues.Rules currentRules() {
        return new BlockClues.Rules(switchedOn(blockToggles), playerBlocks.isOn() ? blockCounts.counts() : Map.of(),
            skyHeight.getInt(), floorMargin.getInt(), ceilingMargin.getInt(), ServerInfo.runsPaper(),
            mc.level.dimension(), growthCount.getInt());
    }

    private static Set<BaseClue> switchedOn(Map<BaseClue, BoolSetting> toggles) {
        Set<BaseClue> on = EnumSet.noneOf(BaseClue.class);
        toggles.forEach((clue, toggle) -> {
            if (toggle.isOn()) {
                on.add(clue);
            }
        });
        return on;
    }

    // A chunk scan came back. What its signs say and the mob of a spawner and the village round a
    // bed are read here on the game thread.
    private void landed(ChunkPos chunk, List<BlockClues.Hit> hits) {
        if (hits.isEmpty() || judgedHere().contains(chunk.pack())) {
            return;
        }
        for (BlockClues.Hit hit : hits) {
            // Your own presence makes plants grow. The chunk may have grown for you.
            if (hit.clue() == BaseClue.GROWTH && OwnSimulation.reached(chunk.pack())) {
                continue;
            }
            String what = switch (hit.clue()) {
                case SIGN -> writing(hit.pos());
                case SPAWNER -> loneSpawner(hit);
                case BED -> loneBed(hit);
                default -> hit.what();
            };
            if (what != null) {
                found(hit.clue(), hit.pos(), what);
            }
        }
    }

    // Such as a lone zombie spawner.
    private String loneSpawner(BlockClues.Hit hit) {
        String mob = SpawnerMob.name(SpawnerMob.of(mc.level.getBlockEntity(hit.pos())));
        return mob.isEmpty() ? hit.what() : "a lone " + mob + " spawner";
    }

    // Null for a bed of a village colour with a village around it. One whose surroundings have
    // not all arrived waits and is judged again.
    private String loneBed(BlockClues.Hit hit) {
        if (!StructureBeds.villageColour(mc.level.getBlockState(hit.pos()).getBlock())) {
            return hit.what();
        }
        if (!Villages.arrived(hit.pos())) {
            waitingBeds.put(ChunkPos.pack(hit.pos()), hit);
            return null;
        }
        return Villages.around(hit.pos(), StructureBeds::villageBlock) ? null : hit.what();
    }

    // A waiting bed leaves once its chunk is marked or gone or the bed is.
    private void judgeWaitingBeds() {
        waitingBeds.values().removeIf(hit -> {
            BlockPos pos = hit.pos();
            if (judgedHere().contains(ChunkPos.pack(pos)) || !mc.level.isLoaded(pos)
                || !StructureBeds.villageColour(mc.level.getBlockState(pos).getBlock())) {
                return true;
            }
            if (!Villages.arrived(pos)) {
                return false;
            }
            if (!Villages.around(pos, StructureBeds::villageBlock)) {
                found(BaseClue.BED, pos, hit.what());
            }
            return true;
        });
    }

    // The writing on a sign or null for a blank one or the igloo's arrows.
    private String writing(BlockPos pos) {
        if (!(mc.level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            return null;
        }
        List<String> front = SignWords.lines(sign, SignTextSlot.FRONT);
        List<String> back = SignWords.lines(sign, SignTextSlot.BACK);
        if ((front.isEmpty() && back.isEmpty()) || SignWords.iglooArrows(front, back)) {
            return null;
        }
        List<String> lines = new ArrayList<>(front);
        lines.addAll(back);
        return "a sign reading " + TextCut.quote(String.join(" ", lines), SIGN_QUOTE);
    }

    // Marks the chunk unless it was judged before. Only a new mark is told and rings.
    private void found(BaseClue clue, BlockPos pos, String what) {
        if (!judgedHere().add(ChunkPos.pack(pos)) || finds.at(pos) != null) {
            return;
        }
        if (finds.add(pos, clue.noun(), what)) {
            notice.tell(clue.noun(), what, pos);
            alarm.ring();
        }
    }

    private LongSet judgedHere() {
        return judged.computeIfAbsent(ServerInfo.worldKey(), key -> new LongOpenHashSet());
    }

    private void scanEntities() {
        Set<BaseClue> wanted = switchedOn(entityToggles);
        Map<Long, ChunkTally> mobs = new HashMap<>();
        Map<Long, ChunkTally> dropped = new HashMap<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) {
                continue;
            }
            EntityClues.Clue clue = EntityClues.judge(entity, wanted);
            if (clue != null) {
                entityFound(clue.clue(), entity.blockPosition(), described(clue, entity));
            }
            if (crowds.isOn() && crowdMobs.contains(entity.getType())) {
                tally(mobs, entity, "", 1);
            }
            if (entity instanceof ItemEntity item && !yours(item) && drops.isOn() && minedBlock(item)) {
                tally(dropped, item, ChatUtil.words(item.getItem().getItem()), item.getItem().getCount());
            }
        }
        crowded = mobs;
        for (ChunkTally crowd : mobs.values()) {
            if (crowd.count >= crowdSize.getInt()) {
                entityFound(BaseClue.CROWD, crowd.pos, "a crowd of " + crowd.count + " mobs");
            }
        }
        for (ChunkTally pile : dropped.values()) {
            if (pile.count >= dropCount.getInt()) {
                entityFound(BaseClue.DROPS, pile.pos,
                    Tally.counted(pile.count, "dropped block") + " such as " + pile.first);
            }
        }
    }

    // A stasis pearl find names whose pearl it is when anyone can tell.
    private static String described(EntityClues.Clue clue, Entity entity) {
        String owner = clue.clue() == BaseClue.PEARL && entity instanceof Projectile pearl
            ? EntityUtil.knownThrower(pearl) : null;
        return owner == null ? clue.what() : clue.what() + " thrown by " + owner;
    }

    private static void tally(Map<Long, ChunkTally> tallies, Entity entity, String first, int amount) {
        tallies.computeIfAbsent(ChunkPos.pack(entity.blockPosition()), key -> new ChunkTally(entity, first))
            .add(entity, amount);
    }

    // An item first seen close to you is yours for as long as it lies there.
    private boolean yours(ItemEntity item) {
        return ownDrops.computeIfAbsent(item.getId(), id -> item.distanceTo(mc.player) <= OWN_DROP_REACH);
    }

    // A whole block such as cobblestone. String and seeds and redstone place blocks as well but
    // come from mobs that die in caves.
    private boolean minedBlock(ItemEntity item) {
        return ItemUtil.isFullBlock(item.getItem().getItem()) && item.getY() <= dropHeight.getValue();
    }

    // A mob or boat next to a marked chunk belongs to the base already marked. Mobs wander.
    private void entityFound(BaseClue clue, BlockPos pos, String what) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (finds.at(pos.offset(dx * SectionPos.SECTION_SIZE, 0, dz * SectionPos.SECTION_SIZE)) != null) {
                    return;
                }
            }
        }
        found(clue, pos, what);
    }

    private String nearestBase() {
        if (!inGame()) {
            return JOIN_FIRST;
        }
        FindLog.Find base = finds.nearest(mc.player.position());
        if (base == null) {
            return "Nothing marked in this dimension";
        }
        String away = Notice.away(mc.player.position(), base.pos());
        String words = "nearest is §f" + base.detail() + "§7 " + away + " at ";
        ChatUtil.component(Component.literal(ChatUtil.from(getName(), words))
            .append(ChatUtil.walkLink(base.pos())).append("§7."));
        return away;
    }

    private String markHere() {
        if (!inGame()) {
            return JOIN_FIRST;
        }
        BlockPos pos = mc.player.blockPosition();
        judgedHere().add(ChunkPos.pack(pos));
        if (finds.at(pos) != null) {
            return "Already marked";
        }
        finds.add(pos, BaseClue.MARK.noun(), "a chunk marked by hand");
        return "Marked this chunk";
    }

    private String unmarkHere() {
        if (!inGame()) {
            return JOIN_FIRST;
        }
        return unmark(finds.at(mc.player.blockPosition())) ? "Unmarked this chunk" : "Nothing marked here";
    }

    private String unmarkNewest() {
        if (!inGame()) {
            return JOIN_FIRST;
        }
        FindLog.Find newest = finds.here().stream().max(Comparator.comparingLong(FindLog.Find::found)).orElse(null);
        return unmark(newest) ? "Unmarked " + newest.detail() : "Nothing to unmark";
    }

    // Forgets a mark and keeps its chunk from being marked again. False when there was none.
    private boolean unmark(FindLog.Find find) {
        if (find == null) {
            return false;
        }
        judgedHere().add(ChunkPos.pack(find.pos()));
        return finds.remove(find);
    }

    private void updateGlow() {
        Glow mode = glow.getValue();
        if (mode != glowMode) {
            glowMode = mode;
            glowScanner.reset();
            lit.clear();
        }
        if (mode == Glow.OFF) {
            return;
        }
        Predicate<BlockState> placed = mode == Glow.NEVER_NATURAL ? state -> PlacedOnly.contains(state.getBlock())
            : state -> !NaturalBlocks.contains(state.getBlock());
        glowScanner.update(glowRange.getInt(), (view, out) -> view.forEachMatching(placed,
            (x, y, z, state) -> out.add(new BlockPos(x, y, z))));
        lit.update(glowScanner.results(), glowLimit.getInt());
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (FindLog.Find find : finds.here()) {
            if (marks.inDrawRange(find)) {
                int colour = colour(find);
                box.draw(batch, DrawBatch.blockBox(find.pos()), colour, colour, true);
            }
        }
        marks.draw(batch, this::colour);
        int fill = ColorUtil.withAlpha(glowColour.getColor(), GLOW_ALPHA);
        for (BlockPos pos : lit.result()) {
            batch.solidBox(DrawBatch.blockBox(pos), fill, true);
        }
    }

    // The count of a crowd is bright once it reaches the crowd size.
    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!crowds.isOn() || !crowdCounts.isOn() || crowded.isEmpty() || !inGame() || !WorldToScreen.update()) {
            return;
        }
        crowded.forEach((key, crowd) -> {
            Vec3 screen = WorldToScreen.project(new Vec3(
                SectionPos.sectionToBlockCoord(ChunkPos.getX(key), SectionPos.SECTION_HALF_SIZE),
                crowd.middleY() + LABEL_LIFT,
                SectionPos.sectionToBlockCoord(ChunkPos.getZ(key), SectionPos.SECTION_HALF_SIZE)));
            if (screen != null) {
                int colour = crowd.count >= crowdSize.getInt() ? entityColour.getColor() : RenderUtil.MUTED_TEXT;
                RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, LABEL_SCALE,
                    List.of(Tally.counted(crowd.count, "mob")), List.of(colour));
            }
        });
    }

    // Marks by blocks and marks by entities and marks by hand each have a colour.
    private int colour(FindLog.Find find) {
        BaseClue clue = BaseClue.byNoun(find.kind());
        BaseClue.Source source = clue == null ? BaseClue.Source.BLOCKS : clue.source();
        return switch (source) {
            case BLOCKS -> blockColour.getColor();
            case ENTITIES -> entityColour.getColor();
            case HAND -> markedColour.getColor();
        };
    }
}
