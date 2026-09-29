package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.ChunkMarks;
import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindSource;
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
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.AlertSound;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChunkActivity;
import com.jellypudding.offlineclient.util.ChunkOrigin;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.WorldWatch;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMaps;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

// Judges chunks fresh or old as they arrive and marks the ones that show someone is near.
// The palettes the server sent come first. A chunk they leave open falls to its liquid.
// Liquid already flowing on arrival means it was loaded from disk and a flow that starts
// right after means it was just generated. An old chunk whose palettes still list a block
// that is gone or was used changed since the server loaded it. Only someone nearby keeps
// a chunk loaded and changes it.
public final class NewChunks extends Module implements FindSource {

    // The letters the chunk marks are saved with.
    private static final byte NEW = 'n';
    private static final byte OLD = 'o';
    private static final byte OLD_VERSION = 'v';
    private static final byte DORMANT = 'd';

    // What every find of activity is called in messages and waypoints.
    private static final String ACTIVITY = "activity";

    // The dimensions Skipped dimensions offers spelt as ServerInfo names them.
    private static final List<String> DIMENSIONS = List.of("Overworld", "Nether", "End");

    // The arrival tick of this many chunks is kept for the liquid check and Show unjudged.
    private static final int MAX_ARRIVALS = 16384;
    // What decided this many verdicts is kept for Show reasons.
    private static final int MAX_REASONS = 16384;
    // This many chunks whose block updates counted are kept from counting again.
    private static final int MAX_UPDATED = 16384;

    // Blocks tick one ring past the simulation distance and a flow there reaches one ring further.
    private static final int OWN_TICKING_MARGIN = 1;
    private static final int SPILL_RINGS = 1;
    // The server sees you where you stood about a round trip ago. A chunk you left within this
    // many ticks may still tick for you.
    private static final int LAG_TICKS = 40;
    // A server can hold a chunk loaded a while after your client lets it go. Paper waits ten
    // seconds by default. A chunk your simulation reached that comes back sooner may carry
    // your own changes.
    private static final long OWN_CHANGES_MS = 60_000;
    private static final int MAX_SIMULATED = 65536;
    // How often the chunks your own simulation reaches are noted.
    private static final int SIMULATED_EVERY_TICKS = 20;

    // A changed chunk this close to one already announced belongs to the same group of players.
    private static final int ALERT_GAP_CHUNKS = 8;
    private static final int MAX_ALERTS = 64;

    // A filled square is a box this thin.
    private static final double SQUARE_THICKNESS = 0.02;
    private static final int SECTION = SectionPos.SECTION_SIZE;
    private static final float PERCENT = 100f;

    // The colour one kind of chunk is drawn in and how strongly.
    private static final class Paint {
        private final ColorSetting colour;
        private final NumberSetting opacity;

        private Paint(String kind, String chunks, String about, float hue, int strength) {
            colour = new ColorSetting(kind + " colour", "Colour of the " + chunks + "." + about, hue, false);
            opacity = new NumberSetting(kind + " opacity",
                "How strongly the " + chunks + " are drawn. At a hundred the edges are solid and the faces "
                    + "follow Fill opacity.",
                strength, 5, 100, 5, "%").min(1).max(100).under(colour, () -> true);
        }

        private Paint under(BoolSetting parent) {
            colour.under(parent);
            return this;
        }

        private Setting<?>[] settings() {
            return new Setting<?>[] {colour, opacity};
        }

        // The colour at the chosen strength for the edges. Faces take a share of it.
        private int line() {
            return ColorUtil.fade(colour.getColor(), opacity.getFloat() / PERCENT);
        }
    }

    // A block change the server sent. Queued on the network thread.
    private record BlockChange(BlockPos pos, BlockState state) {
    }

    // A chunk you stood in and the last tick you stood there.
    private record Stood(long chunk, int tick) {
    }

    private final BoolSetting paletteCheck = new BoolSetting("Palette check",
        "Judges each chunk by the order of the block and biome lists the server sent with it. "
            + "Needs a server on 1.18 or newer.", true);
    private final BoolSetting liquidCheck = new BoolSetting("Liquid check",
        "Judges the chunks the palettes leave open by whether their water and lava were already "
            + "flowing when they arrived.", true);
    private final NumberSetting settle = new NumberSetting("Settle time",
        "How long after a chunk lands a liquid flow still counts as fresh.", 60, 5, 300, 5, "s")
        .min(1).under(liquidCheck);
    private final NumberSetting minSpread = new NumberSetting("Min spread",
        "How far a flow must have run from its source before it proves a chunk old. Raise it on servers that tick chunks before sending them.",
        3, 1, 7, 1, " blocks").min(1).max(7).under(liquidCheck);

    private final BoolSetting oldVersions = new BoolSetting("Old versions",
        "Marks chunks first generated by a game version older than 1.18 in their own colour. "
            + "In the nether that means older than 1.16 and in the End older than 1.13. A dormant chunk "
            + "keeps the dormant colour whilst Dormant chunks is on.", true);
    private final ChoiceListSetting skippedDimensions = new ChoiceListSetting("Skipped dimensions",
        "Dimensions where chunks are never checked for an older game version. Click to pick them.",
        () -> DIMENSIONS).under(oldVersions);
    private final Paint oldVersionPaint = new Paint("Old version", "chunks first generated by an older game version",
        "", 280, 60).under(oldVersions);
    private final AlertSound oldVersionAlarm = new AlertSound("Old version",
        "Rings when a chunk from an older game version loads.", SoundEvents.AMETHYST_BLOCK_CHIME).under(oldVersions);

    private final BoolSetting dormant = new BoolSetting("Dormant chunks",
        "Marks old chunks that nobody has loaded since the server moved to a newer game version in "
            + "their own colour.", true);
    private final Paint dormantPaint = new Paint("Dormant", "dormant chunks",
        " Nobody has been near them since the server updated.", 120, 80).under(dormant);
    private final AlertSound dormantAlarm = new AlertSound("Dormant", "Rings when a dormant chunk loads.",
        SoundEvents.END_PORTAL_FRAME_FILL).under(dormant);

    private final BoolSetting activityCheck = new BoolSetting("Activity check",
        "Marks old chunks whose blocks changed since the server loaded them. Someone is near them "
            + "or was moments ago. Chunks the server ticks for you are only read with Near chunks on.",
        true).startFolded();
    private final BoolSetting usedBlocks = new BoolSetting("Count used blocks",
        "Also counts doors and levers and furnaces that were used since the server loaded the chunk.", true)
        .under(activityCheck);
    private final BoolSetting minedOres = new BoolSetting("Count mined ores",
        "Counts an ore gone from a chunk as activity. Switch it off if Paper anti xray sets it off.", true)
        .under(activityCheck);
    private final BoolSetting nearChunks = new BoolSetting("Near chunks",
        "Also reads the chunks the server ticks for you. Blocks that can change on their own are left out there.",
        false).under(activityCheck);
    private final RegistryListSetting<Block> ignoredOverworld = ignoredList("Overworld").under(activityCheck);
    private final RegistryListSetting<Block> ignoredNether = ignoredList("Nether").under(activityCheck);
    private final RegistryListSetting<Block> ignoredEnd = ignoredList("End").under(activityCheck);
    private final BoolSetting updateCheck = new BoolSetting("Update check",
        "Also marks chunks whose blocks change whilst you watch. Only chunks more than two chunks past the "
            + "server's simulation distance from you count and flowing liquid never does.", true)
        .under(activityCheck);
    private final BoolSetting sectionBoxes = new BoolSetting("Section boxes",
        "Draws a box around each part of a changed chunk at its real height.", true).under(activityCheck);
    private final Paint activePaint = new Paint("Active", "chunks that changed since the server loaded them",
        "", 45, 100).under(activityCheck);
    private final Notice notice = new Notice(this, Notice.Where.CHAT).under(activityCheck);
    private final AlertSound activityAlarm = new AlertSound("Activity",
        "Rings when a changed chunk turns up away from any announced before.", SoundEvents.BELL_RESONATE)
        .under(activityCheck);
    private final FindLog finds = FindLog.byChunk(this);

    private final Paint newPaint = new Paint("New", "fresh chunks",
        " The server generated them moments before it sent them.", 0, 80);
    private final AlertSound newAlarm = new AlertSound("New", "Rings when a fresh chunk loads.", SoundEvents.BELL_BLOCK);
    private final BoolSetting showOld = new BoolSetting("Show old chunks", "Also draws the old chunks.", false);
    private final Paint oldPaint = new Paint("Old", "old chunks", " The server loaded them from its save.", 220, 35)
        .under(showOld);
    private final AlertSound oldAlarm = new AlertSound("Old", "Rings when an old chunk loads.",
        SoundEvents.LODESTONE_COMPASS_LOCK).under(showOld);
    private final BoolSetting showUnjudged = new BoolSetting("Show unjudged",
        "Also draws the chunks that arrived without anything to judge them by.", false);
    private final Paint unjudgedPaint = new Paint("Unjudged", "chunks with no verdict", "", 60, 40)
        .under(showUnjudged);
    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final BoolSetting followHeight = new BoolSetting("Follow height",
        "Draws the squares at your own height instead of a fixed one.", true);
    private final NumberSetting drawHeight = new NumberSetting("Height",
        "The height the squares sit at.", 64, -64, 320, 1)
        .min(-2048).max(2048).unless(followHeight);
    private final NumberSetting distance = new NumberSetting("Distance",
        "How far away a chunk may be and still be drawn.", 16, 4, 64, 1, " chunks")
        .min(1);
    private final BoolSetting showReasons = new BoolSetting("Show reasons",
        "Outlines the section or the liquid block that decided each verdict.", false);
    private final BoolSetting keepMarks = new BoolSetting("Keep chunks",
        "Keeps the marked chunks when you switch this off. Without it only the saved ones come back.", true);
    private final ChunkMarks marks = new ChunkMarks(this);
    private final ActionSetting clearChunks = new ActionSetting("Clear chunks",
        "Forgets the marked chunks of the dimension you are in and empties their file.", this::clearChunks)
        .confirm();
    private final BoolSetting logChunks = new BoolSetting("Log chunks",
        "Writes every verdict and every change found to the game log.", false);
    private final BoolSetting explain = new BoolSetting("Explain limits",
        "Explains the limits in chat when you switch this on.", true);

    // When each chunk landed in ticks. Oldest first.
    private final Map<Long, Integer> arrivals = new BoundedMap<>(MAX_ARRIVALS);
    // The section or liquid block that decided each verdict.
    private final Map<Long, AABB> reasons = new BoundedMap<>(MAX_REASONS);
    // The height of every section that changed in each active chunk found this session. A
    // chunk leaves once its find is gone.
    private final Map<Long, int[]> changedSections = new HashMap<>();
    // The chunks your own simulation reached with the last time each was in reach or held by
    // your client.
    private final Map<Long, Long> simulated = new BoundedMap<>(MAX_SIMULATED, true);
    // The chunks whose block updates already counted this session. Oldest first.
    private final Set<Long> updated = Collections.newSetFromMap(new BoundedMap<>(MAX_UPDATED));
    // The chunks announced in chat. Newest last.
    private final Deque<Long> alerted = new ArrayDeque<>();
    // The chunks you stood in during the last two seconds. Newest last.
    private final Deque<Stood> stood = new ArrayDeque<>();
    // Filled from the network thread and drained on the next tick.
    private final Queue<BlockChange> changes = new ConcurrentLinkedQueue<>();

    // The chunks in range of the last rebuild kept apart by how they are drawn. Colours are
    // resolved at draw time because a rainbow setting moves every frame.
    private final LongList visibleNew = new LongArrayList();
    private final LongList visibleOld = new LongArrayList();
    private final LongList visibleOldVersion = new LongArrayList();
    private final LongList visibleDormant = new LongArrayList();
    private final LongList visibleUnjudged = new LongArrayList();
    private final LongList visibleActive = new LongArrayList();
    private final List<AABB> visibleSections = new ArrayList<>();

    private long visibleAt = Long.MIN_VALUE;
    private int revision;
    private int drawnStamp;
    private int drawnLayout = -1;
    private String suffix;
    private int suffixStamp;

    private final WorldWatch world = new WorldWatch();
    private int ticks;

    public NewChunks() {
        super("NewChunks", "Marks fresh and old chunks as they load and the ones that show someone is near.",
            Category.HUNTING);
        addSettings(paletteCheck, liquidCheck, settle, minSpread);
        addSettings(oldVersions, skippedDimensions);
        addSettings(oldVersionPaint.settings());
        addSettings(oldVersionAlarm.settings());
        addSettings(dormant);
        addSettings(dormantPaint.settings());
        addSettings(dormantAlarm.settings());
        addSettings(activityCheck, usedBlocks, minedOres, nearChunks, ignoredOverworld, ignoredNether, ignoredEnd,
            updateCheck, sectionBoxes);
        addSettings(activePaint.settings());
        addSettings(notice.settings());
        addSettings(activityAlarm.settings());
        for (Setting<?> row : FindsScreen.settingsFor(finds)) {
            row.under(activityCheck);
            addSettings(row);
        }
        addSettings(newPaint.settings());
        addSettings(newAlarm.settings());
        addSettings(showOld);
        addSettings(oldPaint.settings());
        addSettings(oldAlarm.settings());
        addSettings(showUnjudged);
        addSettings(unjudgedPaint.settings());
        addSettings(style.settings());
        addSettings(followHeight, drawHeight, distance, showReasons, keepMarks);
        addSettings(marks.settings());
        addSettings(clearChunks, logChunks, explain);
        searchTags("new chunks", "fresh terrain", "old chunks", "palette", "exploit", "activity", "dormant",
            "old version");
    }

    private static RegistryListSetting<Block> ignoredList(String dimension) {
        return new RegistryListSetting<>("Ignored " + dimension + " blocks",
            "Blocks in the " + dimension + " whose loss or use never counts as activity. Click to pick them.",
            BuiltInRegistries.BLOCK, List.of());
    }

    @Override
    public FindLog findLog() {
        return finds;
    }

    // Rebuilt only when a mark or a find changes.
    @Override
    public String getSuffix() {
        int stamp = stamp();
        if (suffix == null || stamp != suffixStamp) {
            StringBuilder text = new StringBuilder().append(marks.count(NEW)).append(" new ")
                .append(marks.count(OLD)).append(" old");
            addCount(text, marks.count(OLD_VERSION), "old version");
            addCount(text, marks.count(DORMANT), "dormant");
            addCount(text, finds.count(), "active");
            suffix = text.toString();
            suffixStamp = stamp;
        }
        return suffix;
    }

    private static void addCount(StringBuilder text, int count, String what) {
        if (count > 0) {
            text.append(' ').append(count).append(' ').append(what);
        }
    }

    @Override
    protected void onEnable() {
        syncWorld();
        revision++;
        if (explain.isOn()) {
            ChatUtil.message("§bNewChunks §7only judges chunks that load whilst it is on. "
                + "Switch it on before you explore. The palette check needs a server on 1.18 or newer. "
                + "The liquid check can call a fresh chunk old on a server that ticks chunks before "
                + "sending them. Raise Min spread if that happens.");
            if (activityCheck.isOn() && !nearChunks.isOn() && inGame() && readableRings() <= 0) {
                ChatUtil.message("§bNewChunks §7cannot read activity here. The server ticks every chunk it "
                    + "sends you. Switch on Near chunks to read them anyway.");
            }
        }
    }

    @Override
    protected void onDisable() {
        newAlarm.stop();
        oldAlarm.stop();
        oldVersionAlarm.stop();
        dormantAlarm.stop();
        activityAlarm.stop();
        changes.clear();
        arrivals.clear();
        if (!keepMarks.isOn()) {
            marks.forget();
            reasons.clear();
            changedSections.clear();
        }
        revision++;
    }

    // Chunk coordinates mean something different in every world. The marks and finds keep
    // each world apart themselves.
    private void syncWorld() {
        if (!world.changed()) {
            return;
        }
        arrivals.clear();
        reasons.clear();
        changedSections.clear();
        simulated.clear();
        updated.clear();
        alerted.clear();
        stood.clear();
        changes.clear();
        visibleAt = Long.MIN_VALUE;
        revision++;
        ticks = 0;
    }

    // Every later packet is still waiting. No flow update has been written into the chunk
    // and its palettes hold the server's order.
    @Subscribe
    private void onChunkData(ChunkDataEvent event) {
        if (!inGame()) {
            return;
        }
        syncWorld();
        LevelChunk chunk = event.getChunk();
        long key = chunk.getPos().pack();
        arrivals.remove(key);
        arrivals.put(key, ticks);
        revision++;
        int dormantSection = ChunkOrigin.dormantSection(chunk);
        if (dormantSection >= 0) {
            markUpgraded(chunk, key, dormantSection);
            return;
        }
        if (marks.get(key) == 0) {
            judge(chunk, key);
        } else if (activityCheck.isOn() && ageOf(chunk) == ChunkOrigin.Age.OLD) {
            // A chunk sent again keeps its first verdict. Someone may have changed it since.
            readActivity(chunk, key);
        }
    }

    // A chunk loaded for the first time since the server moved to a newer game version. Its
    // palettes carry what the upgrade left and say nothing about its age or about players.
    // With Dormant chunks off its terrain can still show the version it came from.
    private void markUpgraded(LevelChunk chunk, long key, int dormantSection) {
        int oldSection = !dormant.isOn() && checksOldVersions(chunk) ? ChunkOrigin.oldVersionSection(chunk) : -1;
        if (oldSection >= 0) {
            mark(key, OLD_VERSION, sectionBox(chunk, oldSection));
        } else {
            mark(key, dormant.isOn() ? DORMANT : OLD, sectionBox(chunk, dormantSection));
        }
    }

    // An old version is judged before the palettes. A chunk from before 1.18 has its sections
    // below nought built anew the first time it loads and their palettes read fresh.
    private void judge(LevelChunk chunk, long key) {
        ChunkOrigin.Finding palettes = paletteCheck.isOn() || activityCheck.isOn()
            ? ChunkOrigin.readPalettes(chunk) : null;
        int oldSection = checksOldVersions(chunk) ? ChunkOrigin.oldVersionSection(chunk) : -1;
        if (oldSection >= 0) {
            mark(key, OLD_VERSION, sectionBox(chunk, oldSection));
        } else if (palettes != null && paletteCheck.isOn()) {
            boolean fresh = palettes.age() == ChunkOrigin.Age.FRESH;
            mark(key, fresh ? NEW : OLD, sectionBox(chunk, palettes.section()));
        } else if (liquidCheck.isOn()) {
            BlockPos found = findFlowingLiquid(chunk, minSpread.getInt());
            if (found != null) {
                mark(key, OLD, DrawBatch.blockBox(found));
            }
        }
        if (palettes != null && palettes.age() == ChunkOrigin.Age.OLD) {
            readActivity(chunk, key);
        }
    }

    private static ChunkOrigin.Age ageOf(LevelChunk chunk) {
        ChunkOrigin.Finding palettes = ChunkOrigin.readPalettes(chunk);
        return palettes == null ? null : palettes.age();
    }

    private boolean checksOldVersions(LevelChunk chunk) {
        return oldVersions.isOn() && !skippedDimensions.contains(
            ServerInfo.dimensionName(chunk.getLevel().dimension().identifier().toString()));
    }

    private void mark(long key, byte letter, AABB reason) {
        if (!marks.mark(key, letter)) {
            return;
        }
        reasons.put(key, reason);
        revision++;
        alarmFor(letter).ring();
        if (logChunks.isOn()) {
            OfflineClient.LOG.info("NewChunks marked the chunk at {} {} as {}",
                SectionPos.sectionToBlockCoord(ChunkPos.getX(key)), SectionPos.sectionToBlockCoord(ChunkPos.getZ(key)),
                (char) letter);
        }
    }

    private AlertSound alarmFor(byte letter) {
        return switch (letter) {
            case NEW -> newAlarm;
            case OLD_VERSION -> oldVersionAlarm;
            case DORMANT -> dormantAlarm;
            default -> oldAlarm;
        };
    }

    // Reads what changed in an old chunk since the server loaded it. A chunk your own
    // simulation reached lately may hold your own changes and is left alone.
    private void readActivity(LevelChunk chunk, long key) {
        if (!activityCheck.isOn()) {
            return;
        }
        boolean near = !pastOwnTicking(key);
        if ((near && !nearChunks.isOn()) || ownChangesPossible(key)) {
            return;
        }
        List<ChunkActivity.Change> found = ChunkActivity.read(chunk, new ChunkActivity.Rules(usedBlocks.isOn(),
            minedOres.isOn(), near, ServerInfo.runsPaper(), ignoredIn(chunk.getLevel().dimension())));
        if (found.isEmpty()) {
            return;
        }
        int[] heights = found.stream().mapToInt(change -> chunk.getSectionYFromSectionIndex(change.section()))
            .toArray();
        ChunkPos pos = chunk.getPos();
        BlockPos spot = new BlockPos(pos.getMiddleBlockX(), SectionPos.sectionToBlockCoord(heights[0]) + SECTION / 2,
            pos.getMiddleBlockZ());
        foundActivity(key, spot, ChunkActivity.describe(found), heights);
    }

    private Set<Block> ignoredIn(ResourceKey<Level> dimension) {
        if (dimension == Level.OVERWORLD) {
            return ignoredOverworld.resolved();
        }
        if (dimension == Level.NETHER) {
            return ignoredNether.resolved();
        }
        return dimension == Level.END ? ignoredEnd.resolved() : Set.of();
    }

    private void foundActivity(long key, BlockPos spot, String detail, int[] sectionHeights) {
        finds.add(spot, ACTIVITY, detail);
        changedSections.put(key, sectionHeights);
        revision++;
        announce(key, spot, detail);
    }

    // Every change is logged. Only the first of a group is announced and rings.
    private void announce(long key, BlockPos spot, String detail) {
        if (logChunks.isOn()) {
            OfflineClient.LOG.info("NewChunks found {} at {} {} {}", detail, spot.getX(), spot.getY(), spot.getZ());
        }
        if (startsGroup(key)) {
            notice.tell(ACTIVITY, detail, spot);
            activityAlarm.ring();
        }
    }

    // How many rings of the chunks the server sends lie past the ones it ticks around you.
    private int readableRings() {
        return mc.player.connection.serverChunkRadius - ownTickingRings();
    }

    private int ownTickingRings() {
        return mc.player.connection.serverSimulationDistance + OWN_TICKING_MARGIN;
    }

    private boolean pastOwnTicking(long key) {
        ChunkPos me = mc.player.chunkPosition();
        return Mth.chessboardDistance(ChunkPos.getX(key), ChunkPos.getZ(key), me.x(), me.z()) > ownTickingRings();
    }

    private boolean ownChangesPossible(long key) {
        Long held = simulated.get(key);
        return held != null && System.currentTimeMillis() - held < OWN_CHANGES_MS;
    }

    // Notes the chunks your own simulation reaches whether your client holds them yet or not.
    // The server may tick a chunk for you before it sends it. Keeps the time fresh for each
    // reached chunk your client still holds.
    private void noteOwnSimulation() {
        long now = System.currentTimeMillis();
        ChunkPos me = mc.player.chunkPosition();
        int reach = ownTickingRings() + SPILL_RINGS;
        int view = Math.max(mc.player.connection.serverChunkRadius, reach);
        ClientChunkCache cache = mc.level.getChunkSource();
        for (int dx = -view; dx <= view; dx++) {
            for (int dz = -view; dz <= view; dz++) {
                int x = me.x() + dx;
                int z = me.z() + dz;
                long key = ChunkPos.pack(x, z);
                boolean reached = Math.max(Math.abs(dx), Math.abs(dz)) <= reach;
                if (reached || (simulated.containsKey(key) && cache.getChunk(x, z, ChunkStatus.FULL, false) != null)) {
                    simulated.put(key, now);
                }
            }
        }
    }

    // Remembers the chunks you stood in during the last two seconds.
    private void noteStanding() {
        long here = mc.player.chunkPosition().pack();
        Stood last = stood.peekLast();
        if (last != null && last.chunk() == here) {
            stood.removeLast();
        }
        stood.addLast(new Stood(here, ticks));
        while (ticks - stood.peekFirst().tick() > LAG_TICKS) {
            stood.removeFirst();
        }
    }

    // True when no chunk you stood in lately ticks the chunk or lets a flow spill into it.
    private boolean pastOwnReach(long key) {
        int reach = ownTickingRings() + SPILL_RINGS;
        for (Stood spot : stood) {
            if (Mth.chessboardDistance(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkPos.getX(spot.chunk()),
                ChunkPos.getZ(spot.chunk())) <= reach) {
                return false;
            }
        }
        return true;
    }

    // Remembers the chunk when it is far enough from every earlier announcement to earn one.
    private boolean startsGroup(long key) {
        for (long earlier : alerted) {
            if (Mth.chessboardDistance(ChunkPos.getX(earlier), ChunkPos.getZ(earlier),
                ChunkPos.getX(key), ChunkPos.getZ(key)) < ALERT_GAP_CHUNKS) {
                return false;
            }
        }
        alerted.addLast(key);
        if (alerted.size() > MAX_ALERTS) {
            alerted.removeFirst();
        }
        return true;
    }

    private static AABB sectionBox(LevelChunk chunk, int section) {
        return sectionBox(chunk.getPos().pack(), chunk.getSectionYFromSectionIndex(section));
    }

    private static AABB sectionBox(long key, int sectionY) {
        double x = SectionPos.sectionToBlockCoord(ChunkPos.getX(key));
        double y = SectionPos.sectionToBlockCoord(sectionY);
        double z = SectionPos.sectionToBlockCoord(ChunkPos.getZ(key));
        return new AABB(x, y, z, x + SECTION, y + SECTION, z + SECTION);
    }

    // Fired on the netty thread. Flows feed the liquid check and every change feeds the
    // update check.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundBlockUpdatePacket update) {
            queueChange(update.getPos(), update.getBlockState());
        } else if (event.getPacket() instanceof ClientboundSectionBlocksUpdatePacket update) {
            update.runUpdates(this::queueChange);
        }
    }

    private void queueChange(BlockPos pos, BlockState state) {
        if ((updateCheck.isOn() && activityCheck.isOn()) || (liquidCheck.isOn() && isFlowing(state))) {
            changes.add(new BlockChange(pos.immutable(), state));
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        syncWorld();
        ticks++;
        noteStanding();
        if (ticks % SIMULATED_EVERY_TICKS == 0) {
            noteOwnSimulation();
        }
        Long2ByteMap here = marks.here();
        BlockChange change;
        while ((change = changes.poll()) != null) {
            long key = ChunkPos.pack(change.pos());
            boolean flowing = isFlowing(change.state());
            if (liquidCheck.isOn() && flowing && here.get(key) == 0) {
                flowAfterArrival(key, change.pos());
            }
            if (updateCheck.isOn() && activityCheck.isOn() && !flowing && pastOwnReach(key) && updated.add(key)) {
                foundUpdate(key, change);
            }
        }
    }

    // A flow that turns up soon after the chunk landed means the chunk is fresh.
    private void flowAfterArrival(long key, BlockPos pos) {
        Integer seen = arrivals.get(key);
        if (seen != null && ticks - seen <= settle.getInt() * SharedConstants.TICKS_PER_SECOND) {
            mark(key, NEW, DrawBatch.blockBox(pos));
        }
    }

    // A block changed in a chunk that only ticks for someone else. A chunk that already has a
    // find keeps its words.
    private void foundUpdate(long key, BlockChange change) {
        String detail = change.state().isAir() ? "a block broken"
            : ChatUtil.words(change.state().getBlock()) + " changing";
        if (finds.at(change.pos()) == null) {
            foundActivity(key, change.pos(), detail, new int[] {SectionPos.blockToSectionCoord(change.pos().getY())});
        } else {
            announce(key, change.pos(), detail);
        }
    }

    // The first flowing liquid in the chunk or null. The palette test is only a prefilter
    // and every hit is confirmed against the real blocks.
    private static BlockPos findFlowingLiquid(LevelChunk chunk, int spread) {
        boolean fastLava = chunk.getLevel().environmentAttributes().getDimensionValue(EnvironmentAttributes.FAST_LAVA);
        LevelChunkSection[] sections = chunk.getSections();
        int minY = chunk.getMinY();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.hasFluid()
                || !section.maybeHas(NewChunks::isFlowing)) {
                continue;
            }
            for (int y = 0; y < SECTION; y++) {
                for (int x = 0; x < SECTION; x++) {
                    for (int z = 0; z < SECTION; z++) {
                        if (spreadOf(section.getBlockState(x, y, z), fastLava) >= spread) {
                            return new BlockPos(minX + x, minY + i * SECTION + y, minZ + z);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean isFlowing(BlockState state) {
        FluidState fluid = state.getFluidState();
        return !fluid.isEmpty() && !fluid.isSource();
    }

    // How many blocks a flow has run from its source. A source is nought and falling liquid
    // reads as one. Water loses a level for each block. Lava loses two unless the dimension
    // makes it fast.
    private static int spreadOf(BlockState state, boolean fastLava) {
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty() || fluid.isSource()) {
            return 0;
        }
        int levels = Math.max(1, 8 - fluid.getAmount());
        return fluid.is(FluidTags.LAVA) && !fastLava ? (levels + 1) / 2 : levels;
    }

    private String clearChunks() {
        int count = marks.clearDimension();
        arrivals.clear();
        reasons.clear();
        revision++;
        return Tally.cleared(count, "chunk");
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        ChunkPos me = mc.player.chunkPosition();
        long here = me.pack();
        int stamp = stamp();
        int layout = layoutKey();
        if (here != visibleAt || stamp != drawnStamp || layout != drawnLayout) {
            rebuildVisible(me.x(), me.z());
            visibleAt = here;
            drawnStamp = stamp;
            drawnLayout = layout;
        }

        double y = followHeight.isOn() ? mc.player.getY() : drawHeight.getValue();
        DrawBatch batch = event.getBatch();
        drawSquares(batch, visibleOld, oldPaint.line(), y);
        drawSquares(batch, visibleUnjudged, unjudgedPaint.line(), y);
        drawSquares(batch, visibleOldVersion, oldVersionPaint.line(), y);
        drawSquares(batch, visibleDormant, dormantPaint.line(), y);
        drawSquares(batch, visibleNew, newPaint.line(), y);
        drawSquares(batch, visibleActive, activePaint.line(), y);
        int active = activePaint.line();
        for (AABB box : visibleSections) {
            style.draw(batch, box, active, active, true);
        }
        if (showReasons.isOn()) {
            markReasons(batch, visibleNew, newPaint);
            markReasons(batch, visibleOld, oldPaint);
            markReasons(batch, visibleOldVersion, oldVersionPaint);
            markReasons(batch, visibleDormant, dormantPaint);
        }
    }

    // Moves whenever what is drawn could change.
    private int stamp() {
        return (revision * 31 + marks.revision()) * 31 + finds.revision();
    }

    // Draws what proved the verdict. Lets you check the call yourself.
    private void markReasons(DrawBatch batch, LongList chunks, Paint paint) {
        int color = paint.colour.getColor();
        for (long key : chunks) {
            AABB box = reasons.get(key);
            if (box != null) {
                batch.outlineBox(box, color, true);
            }
        }
    }

    private void drawSquares(DrawBatch batch, LongList chunks, int color, double y) {
        int fill = ColorUtil.fade(color, style.fillShare());
        for (long key : chunks) {
            double x1 = SectionPos.sectionToBlockCoord(ChunkPos.getX(key));
            double z1 = SectionPos.sectionToBlockCoord(ChunkPos.getZ(key));
            if (style.drawsLines()) {
                batch.flatRect(x1, z1, x1 + SECTION, z1 + SECTION, y, color, true);
            }
            if (style.drawsSides()) {
                batch.solidBox(new AABB(x1, y, z1, x1 + SECTION, y + SQUARE_THICKNESS, z1 + SECTION), fill, true);
            }
        }
    }

    // The settings that decide which chunks land in the visible lists.
    private int layoutKey() {
        return (showOld.isOn() ? 1 : 0) | (showUnjudged.isOn() ? 2 : 0) | (oldVersions.isOn() ? 4 : 0)
            | (dormant.isOn() ? 8 : 0) | (activityCheck.isOn() ? 16 : 0) | (sectionBoxes.isOn() ? 32 : 0)
            | distance.getInt() << 6;
    }

    // Walks whichever is smaller of the square around the player and the marks. A long draw
    // distance then costs no more than the chunks there are. An active chunk is drawn as
    // active whatever its mark.
    private void rebuildVisible(int centerX, int centerZ) {
        visibleNew.clear();
        visibleOld.clear();
        visibleOldVersion.clear();
        visibleDormant.clear();
        visibleUnjudged.clear();
        visibleActive.clear();
        visibleSections.clear();
        int limit = distance.getInt();
        LongSet active = collectActive(centerX, centerZ, limit);
        Long2ByteMap here = marks.here();
        long side = 2L * limit + 1;
        if (side * side > here.size()) {
            for (Long2ByteMap.Entry entry : Long2ByteMaps.fastIterable(here)) {
                long key = entry.getLongKey();
                if (inRange(key, centerX, centerZ, limit) && !active.contains(key)) {
                    sortVisible(key, entry.getByteValue());
                }
            }
        } else {
            for (int x = centerX - limit; x <= centerX + limit; x++) {
                for (int z = centerZ - limit; z <= centerZ + limit; z++) {
                    long key = ChunkPos.pack(x, z);
                    byte letter = here.get(key);
                    if (letter != 0 && !active.contains(key)) {
                        sortVisible(key, letter);
                    }
                }
            }
        }
        if (showUnjudged.isOn()) {
            for (long key : arrivals.keySet()) {
                if (inRange(key, centerX, centerZ, limit) && here.get(key) == 0 && !active.contains(key)) {
                    visibleUnjudged.add(key);
                }
            }
        }
    }

    // The active chunks in range with the boxes of their changed sections. Drops the heights
    // of every chunk whose find was removed or cleared.
    private LongSet collectActive(int centerX, int centerZ, int limit) {
        LongSet active = new LongOpenHashSet();
        if (!activityCheck.isOn()) {
            return active;
        }
        LongSet found = new LongOpenHashSet();
        for (FindLog.Find find : finds.here()) {
            long key = ChunkPos.pack(find.pos());
            found.add(key);
            if (!inRange(key, centerX, centerZ, limit) || !active.add(key)) {
                continue;
            }
            visibleActive.add(key);
            if (sectionBoxes.isOn()) {
                int[] heights = changedSections.get(key);
                if (heights == null) {
                    heights = new int[] {SectionPos.blockToSectionCoord(find.pos().getY())};
                }
                for (int height : heights) {
                    visibleSections.add(sectionBox(key, height));
                }
            }
        }
        changedSections.keySet().removeIf(key -> !found.contains((long) key));
        return active;
    }

    private static boolean inRange(long key, int centerX, int centerZ, int limit) {
        return Math.abs(ChunkPos.getX(key) - centerX) <= limit && Math.abs(ChunkPos.getZ(key) - centerZ) <= limit;
    }

    // An old version or dormant mark whose own colour is switched off draws as old.
    private void sortVisible(long key, byte letter) {
        LongList list = switch (letter) {
            case NEW -> visibleNew;
            case OLD_VERSION -> oldVersions.isOn() ? visibleOldVersion : visibleOld;
            case DORMANT -> dormant.isOn() ? visibleDormant : visibleOld;
            default -> visibleOld;
        };
        if (list != visibleOld || showOld.isOn()) {
            list.add(key);
        }
    }
}
