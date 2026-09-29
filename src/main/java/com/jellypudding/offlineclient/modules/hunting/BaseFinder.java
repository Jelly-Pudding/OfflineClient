package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindSource;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.gui.FindsScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.FindLines;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.AlertSound;
import com.jellypudding.offlineclient.util.BaseClue;
import com.jellypudding.offlineclient.util.BlockClues;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChunkScanner;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityClues;
import com.jellypudding.offlineclient.util.NearestCut;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.SignWords;
import com.jellypudding.offlineclient.util.SpawnerMob;
import com.jellypudding.offlineclient.worldgen.BaseBlocks;
import com.jellypudding.offlineclient.worldgen.NaturalBlocks;
import com.jellypudding.offlineclient.worldgen.PlacedOnly;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;

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
public final class BaseFinder extends Module implements FindSource {

    // What the glow lights up.
    private enum Glow { OFF, NEVER_NATURAL, PLACED }

    // A list of blocks with its switch and the count one chunk needs.
    private static final class BlockGroup {

        private final BaseClue clue;
        private final BoolSetting enabled;
        private final NumberSetting needed;
        private final RegistryListSetting<Block> blocks;

        private BlockGroup(BaseClue clue, String name, String prefix, String about, List<Block> defaults,
                           int count, boolean on) {
            this.clue = clue;
            enabled = new BoolSetting(name, about, on).startFolded();
            needed = new NumberSetting(prefix + " needed", "How many of the listed blocks one chunk must hold.",
                count, 1, 64, 1).min(1).under(enabled);
            blocks = new RegistryListSetting<>(prefix + " list",
                "The blocks this group counts. Each block it starts with is one the world never places. "
                    + "Click to pick them.", BuiltInRegistries.BLOCK, defaults).under(enabled);
        }

        private Setting<?>[] settings() {
            return new Setting<?>[] {enabled, needed, blocks};
        }

        // Null whilst the group is off.
        private BlockClues.Group rule() {
            return enabled.isOn() ? new BlockClues.Group(clue, blocks.resolved(), needed.getInt()) : null;
        }
    }

    // The mobs of a crowd counted in one chunk and where the first of them stood.
    private static final class Crowd {

        private final BlockPos pos;
        private int count;

        private Crowd(BlockPos pos) {
            this.pos = pos;
        }
    }

    // An outpost tower on the highest peak ends at 276. Nothing else the world builds reaches it.
    private static final int SKY_HEIGHT = 277;

    // The world spawns at most eight creatures in a pack and a further pack one time in ten. A
    // chunk only holds this many after three full packs.
    private static final int CROWD_SIZE = 24;

    // A base spans several chunks and they load together.
    private static final int QUIET_SECONDS = 5;

    // Twice a second is quick enough for mobs that wander and boats left by a shore.
    private static final int ENTITY_SCAN_TICKS = 10;

    // The writing quoted from a sign stops after this many characters.
    private static final int SIGN_QUOTE = 40;

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

    private final List<BlockGroup> groups = List.of(
        new BlockGroup(BaseClue.RARE, "Rare blocks", "Rare",
            "Marks chunks with valuables or heads such as beacons and netherite blocks.", BaseBlocks.RARE, 1, true),
        new BlockGroup(BaseClue.BUILDING, "Building blocks", "Building",
            "Marks chunks built from blocks such as concrete and stripped logs.", BaseBlocks.BUILDING, 6, true),
        new BlockGroup(BaseClue.WORKSTATIONS, "Workstations", "Workstation",
            "Marks chunks with blocks players use such as enchanting tables and anvils.", BaseBlocks.WORKSTATIONS,
            1, true),
        new BlockGroup(BaseClue.STORAGE, "Storage", "Storage",
            "Marks chunks with shulker boxes or shelves or chiseled bookshelves.", BaseBlocks.STORAGE, 1, true),
        new BlockGroup(BaseClue.FURNISHING, "Furnishing", "Furnishing",
            "Marks chunks with signs and banners and candles and plants only a player puts down.",
            BaseBlocks.FURNISHING, 3, true),
        new BlockGroup(BaseClue.REDSTONE, "Redstone", "Redstone",
            "Marks chunks with pistons and observers and rails and other redstone parts.", BaseBlocks.REDSTONE, 3,
            true),
        new BlockGroup(BaseClue.CUSTOM, "Custom blocks", "Custom",
            "Marks chunks with the blocks you pick. Give a block the world also places a higher count.", List.of(),
            1, false));

    private final NumberSetting floorMargin = new NumberSetting("Floor margin",
        "Block clues leave out this many layers at the bottom of the world. Raise it on a server that puts "
            + "its own blocks down there.", 0, 0, 64, 1, " layers").min(0);
    private final NumberSetting ceilingMargin = new NumberSetting("Ceiling margin",
        "Block clues leave out this many layers at the top of the world. Raise it on a server that puts "
            + "its own blocks up there.", 0, 0, 64, 1, " layers").min(0);

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

    private final FindLog finds = FindLog.byChunk(this);
    private final Notice notice = new Notice(this, Notice.Where.CHAT, QUIET_SECONDS);
    private final AlertSound alarm = new AlertSound("Rings when a chunk is marked.", SoundEvents.BEACON_ACTIVATE);
    private final ColorSetting blockColour = new ColorSetting("Block colour",
        "Colour of the boxes and lines and columns of chunks marked by their blocks.", ORANGE, false);
    private final ColorSetting entityColour = new ColorSetting("Entity colour",
        "Colour of the boxes and lines and columns of chunks marked by mobs and boats and frames.", SKY_BLUE, false);
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

    private final Map<BaseClue, BoolSetting> blockToggles = Map.of(BaseClue.SIGN, signs, BaseClue.PORTAL, portals,
        BaseClue.BUBBLES, bubbles, BaseClue.SKY, sky, BaseClue.BEDROCK, bedrock, BaseClue.ROOF, roof,
        BaseClue.SPAWNER, spawners);
    private final Map<BaseClue, BoolSetting> entityToggles = Map.of(BaseClue.DECORATION, decorations,
        BaseClue.PEARL, pearls, BaseClue.NAMED, named, BaseClue.VILLAGER, villagers, BaseClue.BOAT, boats,
        BaseClue.PET, pets);

    private final ChunkScanner<BlockClues.Hit> scanner = new ChunkScanner<BlockClues.Hit>().onResult(this::landed);
    private final ChunkScanner<BlockPos> glowScanner = new ChunkScanner<>(0);
    private final NearestCut<BlockPos> lit = new NearestCut<>(BlockPos::distToCenterSqr);
    // The chunks judged in each world since this was switched on. A mark taken away stays away.
    private final Map<String, LongSet> judged = new HashMap<>();
    // The settings the chunks in the scanner were read by.
    private BlockClues.Rules rules;
    private Glow glowMode;
    private int entityTicks;

    public BaseFinder() {
        super("BaseFinder", "Marks the chunks around you where players built or lived.", Category.HUNTING);
        addSettings(signs, portals, bubbles, sky, skyHeight, bedrock, roof, spawners);
        for (BlockGroup group : groups) {
            addSettings(group.settings());
        }
        addSettings(floorMargin, ceilingMargin);
        addSettings(decorations, pearls, named, villagers, boats, pets, crowds, crowdSize, crowdMobs);
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
            "player blocks", "factions");
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
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        scanner.markChanged(event.getPacket());
        glowScanner.markChanged(event.getPacket());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        BlockClues.Rules now = currentRules();
        if (!now.equals(rules)) {
            // A changed setting reads every chunk again. Chunks already marked stay quiet.
            rules = now;
            scanner.reset();
        }
        scanner.update(ChunkScanner.heldRadius(), (view, out) -> BlockClues.scan(now, view, out));
        if (++entityTicks >= ENTITY_SCAN_TICKS) {
            entityTicks = 0;
            scanEntities();
        }
        updateGlow();
    }

    private BlockClues.Rules currentRules() {
        List<BlockClues.Group> on = new ArrayList<>();
        for (BlockGroup group : groups) {
            BlockClues.Group rule = group.rule();
            if (rule != null) {
                on.add(rule);
            }
        }
        return new BlockClues.Rules(switchedOn(blockToggles), on, skyHeight.getInt(), floorMargin.getInt(),
            ceilingMargin.getInt(), ServerInfo.runsPaper(), mc.level.dimension());
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

    // A chunk scan came back. What its signs say and the mob of a spawner are read here on the
    // game thread.
    private void landed(ChunkPos chunk, List<BlockClues.Hit> hits) {
        if (hits.isEmpty() || judgedHere().contains(chunk.pack())) {
            return;
        }
        for (BlockClues.Hit hit : hits) {
            String what = switch (hit.clue()) {
                case SIGN -> writing(hit.pos());
                case SPAWNER -> loneSpawner(hit);
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
        String text = String.join(" ", lines);
        return "a sign reading " + (text.length() > SIGN_QUOTE ? text.substring(0, SIGN_QUOTE) + "..." : text);
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
        Map<Long, Crowd> crowded = new HashMap<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) {
                continue;
            }
            EntityClues.Clue clue = EntityClues.judge(entity, wanted);
            if (clue != null) {
                entityFound(clue.clue(), entity.blockPosition(), clue.what());
            }
            if (crowds.isOn() && crowdMobs.contains(entity.getType())) {
                crowded.computeIfAbsent(ChunkPos.pack(entity.blockPosition()),
                    key -> new Crowd(entity.blockPosition())).count++;
            }
        }
        for (Crowd crowd : crowded.values()) {
            if (crowd.count >= crowdSize.getInt()) {
                entityFound(BaseClue.CROWD, crowd.pos, "a crowd of " + crowd.count + " mobs");
            }
        }
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
        ChatUtil.component(Component.literal("§bBaseFinder §7nearest is §f" + base.detail() + "§7 " + away + " at ")
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
