package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ChunkDataEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.OreMarks;
import com.jellypudding.offlineclient.render.OreMarks.Spot;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.AntiXray;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.LoadedChunks;
import com.jellypudding.offlineclient.util.MoveGate;
import com.jellypudding.offlineclient.util.PaletteCells;
import com.jellypudding.offlineclient.util.WorldWatch;
import com.jellypudding.offlineclient.worldgen.OreKind;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

// Paper resends the hidden blocks one and two steps from any block a player digs at within reach. A
// change of dig face is the one dig packet that changes nothing else on the server. What turns up
// is only taken from block updates because anti xray never touches those.
public final class OreProbe extends Module {

    // What one section's palette shows. The ore kinds it may hide with one bit for each kind.
    // Marked when it bears the marks Paper's anti xray leaves. Listing when it lists any block
    // Paper hides.
    private record Reading(int kinds, boolean marked, boolean listing) {
    }

    // The blocks Paper resends round a dig. Every block one or two steps away along the grid.
    private static final int[][] AROUND = around();

    // Kept in hand for a pose or a step the server has not taken in yet.
    private static final double MARGIN = 0.5;
    // Only a server that lends a long reach comes near this. Further would look over too many
    // blocks each survey.
    private static final double MOST_REACH = 8;
    private static final int SPREAD = 2;

    private static final int[][] NEAR = near((int) Math.ceil(MOST_REACH) + SPREAD + 1);

    // In its other modes Paper lists its whole hidden list in every low section. A real section
    // seldom holds this many of those blocks.
    private static final int LISTED_IN_FULL = 12;
    private static final int EVERY_KIND = -1;

    private static final Reading NOTHING = new Reading(0, false, false);
    // A section on the global palette lists nothing and may hide any ore.
    private static final Reading UNLISTED = new Reading(EVERY_KIND, false, false);

    private static final int JUDGED_AFTER = 64;
    // The server hides its ores the way the probes uncover when one in this many of them bears a
    // mark. Paper marks nearly all. Elsewhere a mark is only the entry a removed block left behind.
    private static final int MARKED_ONE_IN = 8;
    private static final int READ_RADIUS = 2;

    private static final int SURVEY_TICKS = 10;
    // Packets left for the rest of the tick and for other modules.
    private static final int HEADROOM = 40;
    private static final int UNANSWERED_SECONDS = 15;
    private static final int UNANSWERED_TICKS = UNANSWERED_SECONDS * SharedConstants.TICKS_PER_SECOND;
    private static final int PURGE_TICKS = 200;

    private static final long NONE = Long.MIN_VALUE;

    private final OreMarks marks = new OreMarks(
        "The ores to show. Raw iron and copper blocks count as their ore.", plainOres());
    private final NumberSetting speed = new NumberSetting("Speed",
        "How many dig packets it sends each tick. Each one uncovers the blocks around one block.",
        4, 1, 20, 1);

    private final WorldWatch world = new WorldWatch();
    // The ore kinds each section may hide with one bit for each kind. Read as its chunk arrives.
    private final Long2IntOpenHashMap hides = new Long2IntOpenHashMap();
    // The blocks the server was asked to resend with one bit for each block of a section.
    private final Long2ObjectOpenHashMap<BitSet> asked = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<OreKind> found = new Long2ObjectOpenHashMap<>();
    // Block updates read on the network thread and handled on the next tick.
    private final Queue<Packet<?>> updates = new ConcurrentLinkedQueue<>();

    private final LongArrayList suspects = new LongArrayList();
    private final LongOpenHashSet suspected = new LongOpenHashSet();
    private int nextSuspect;
    private long surveyedFrom = NONE;
    private int surveyedAt;
    private int ticks;
    private int probingTicks;
    private boolean stale;
    private List<Spot> shown = List.of();
    private int listing;
    private int marked;
    private boolean judged;

    public OreProbe() {
        super("OreProbe", "Uncovers the ores anti xray hides within about seven blocks of you.", Category.RENDER);
        addSettings(marks.picks(), speed);
        addSettings(marks.look());
        searchTags("ore", "xray", "anti xray", "ore finder", "dig", "probe");
        marks.picks().onChange(() -> {
            stale = true;
            surveyedFrom = NONE;
        });
    }

    private static List<OreKind> plainOres() {
        return Arrays.stream(OreKind.values()).filter(kind -> kind.ore() == kind).toList();
    }

    @Override
    public String getSuffix() {
        return count(shown.size());
    }

    @Override
    protected void onEnable() {
        clear();
        if (inGame()) {
            world.accept();
            readAround();
        }
    }

    @Override
    protected void onDisable() {
        clear();
        world.forget();
    }

    private void clear() {
        hides.clear();
        asked.clear();
        found.clear();
        updates.clear();
        suspects.clear();
        suspected.clear();
        nextSuspect = 0;
        surveyedFrom = NONE;
        probingTicks = 0;
        shown = List.of();
        marks.clear();
        listing = 0;
        marked = 0;
        judged = false;
    }

    private void readAround() {
        ChunkPos centre = mc.player.chunkPosition();
        for (int dx = -READ_RADIUS; dx <= READ_RADIUS; dx++) {
            for (int dz = -READ_RADIUS; dz <= READ_RADIUS; dz++) {
                LevelChunk chunk = mc.level.getChunkSource()
                    .getChunk(centre.x() + dx, centre.z() + dz, ChunkStatus.FULL, false);
                if (chunk != null) {
                    readChunk(chunk);
                }
            }
        }
    }

    // Every handler asks first. A chunk of a new world can arrive before the tick that would notice it.
    private void checkWorld() {
        if (world.changed()) {
            clear();
        }
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        if (packet instanceof ClientboundBlockUpdatePacket || packet instanceof ClientboundSectionBlocksUpdatePacket) {
            updates.add(packet);
        }
    }

    // The palettes are read now whilst they still list what the server sent.
    @Subscribe
    private void onChunkData(ChunkDataEvent event) {
        if (!inGame()) {
            return;
        }
        checkWorld();
        readChunk(event.getChunk());
        surveyedFrom = NONE;
    }

    private void readChunk(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            int sectionY = chunk.getSectionYFromSectionIndex(i);
            long key = SectionPos.asLong(chunk.getPos().x(), sectionY, chunk.getPos().z());
            hides.put(key, readSection(sections[i], sectionY));
            asked.remove(key);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        checkWorld();
        ticks++;
        Packet<?> packet;
        while ((packet = updates.poll()) != null) {
            switch (packet) {
                case ClientboundBlockUpdatePacket update -> heard(update.getPos(), update.getBlockState());
                case ClientboundSectionBlocksUpdatePacket section -> section.runUpdates(this::heard);
                default -> {
                }
            }
        }
        if (ticks % PURGE_TICKS == 0) {
            purge();
        }
        if (stale) {
            stale = false;
            shown = picked();
        }
        marks.update(shown);
        judge();
        if (probingTicks >= UNANSWERED_TICKS) {
            disable("OreProbe went off. Nothing came back in " + UNANSWERED_SECONDS
                + " seconds of probing. This server seems not to hide ores or not to resend the hidden ones.");
        }
    }

    private void judge() {
        boolean alone = mc.hasSingleplayerServer();
        if (judged || !alone && listing < JUDGED_AFTER) {
            return;
        }
        judged = true;
        if (alone) {
            ChatUtil.error("OreProbe can do nothing in single player. Nothing is hidden there.");
        } else if (marked * MARKED_ONE_IN < listing) {
            ChatUtil.error("OreProbe can do nothing here. This server sends its ores as they are or hides them in a way"
                + " OreProbe cannot uncover.");
        }
    }

    // A block the server sent. It is the real block whatever anti xray hides.
    private void heard(BlockPos pos, BlockState state) {
        long key = pos.asLong();
        if (isAsked(key)) {
            probingTicks = 0;
        }
        OreKind kind = OreMarks.kindOf(state.getBlock());
        if (kind != null) {
            stale |= found.put(key, kind) != kind;
        } else {
            stale |= found.remove(key) != null;
        }
    }

    private List<Spot> picked() {
        List<Spot> spots = new ArrayList<>();
        for (Long2ObjectMap.Entry<OreKind> entry : found.long2ObjectEntrySet()) {
            if (marks.picked(entry.getValue())) {
                long key = entry.getLongKey();
                spots.add(new Spot(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), entry.getValue()));
            }
        }
        return spots;
    }

    private void purge() {
        hides.keySet().removeIf(key -> !LoadedChunks.hasChunk(SectionPos.x(key), SectionPos.z(key)));
        asked.keySet().removeIf(key -> !LoadedChunks.hasChunk(SectionPos.x(key), SectionPos.z(key)));
        stale |= found.keySet().removeIf(key -> !LoadedChunks.has(BlockPos.getX(key), BlockPos.getZ(key)));
    }

    // Probes go out right after the movement packet. The server then measures reach from where it was just told.
    @Subscribe
    private void onPostMotion(PostMotionEvent event) {
        if (!inGame() || !mayProbe()) {
            return;
        }
        checkWorld();
        int allowed = Math.min(speed.getInt(), MoveGate.spare() - HEADROOM);
        if (allowed <= 0) {
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        double reach = Math.min(BlockUtil.serverReachLimit(), MOST_REACH) - MARGIN;
        long from = BlockPos.containing(eye).asLong();
        if (from != surveyedFrom || ticks - surveyedAt >= SURVEY_TICKS) {
            survey(eye, reach);
            surveyedFrom = from;
            surveyedAt = ticks;
        }
        int sent = 0;
        while (sent < allowed && nextSuspect < suspects.size()) {
            long suspect = suspects.getLong(nextSuspect++);
            if (isAsked(suspect)) {
                continue;
            }
            long probe = bestProbe(suspect, eye, reach);
            if (probe != NONE) {
                send(probe);
                sent++;
            }
        }
        if (sent > 0) {
            probingTicks++;
        }
    }

    // The server does nothing with a dig from a player who is dead or asleep or before the
    // world has loaded. Whilst Latency holds positions back it measures reach from somewhere else.
    private boolean mayProbe() {
        ClientPacketListener connection = mc.getConnection();
        return connection != null && connection.hasClientLoaded() && !mc.player.isDeadOrDying()
            && !mc.player.isSleeping() && !MoveGate.held(false);
    }

    private void survey(Vec3 eye, double reach) {
        suspects.clear();
        suspected.clear();
        nextSuspect = 0;
        int picked = pickedKinds();
        if (picked == 0) {
            return;
        }
        // A block counts when a probe in reach can uncover it. The box corner adds a block.
        double furthest = reach + SPREAD + 1;
        BlockPos origin = BlockPos.containing(eye);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] offset : NEAR) {
            if (offset[0] * offset[0] + offset[1] * offset[1] + offset[2] * offset[2] > furthest * furthest) {
                break;
            }
            pos.set(origin.getX() + offset[0], origin.getY() + offset[1], origin.getZ() + offset[2]);
            if (mc.level.isOutsideBuildHeight(pos) || !LoadedChunks.has(pos.getX(), pos.getZ())) {
                continue;
            }
            long key = pos.asLong();
            if ((hidesAt(pos) & picked) == 0 || isAsked(key)) {
                continue;
            }
            Block block = mc.level.getBlockState(pos).getBlock();
            if ((AntiXray.FILLERS.contains(block) || AntiXray.HIDDEN.contains(block))
                && !AntiXray.shownTruly(mc.level, pos)) {
                suspects.add(key);
                suspected.add(key);
            }
        }
    }

    private int pickedKinds() {
        int picked = 0;
        for (OreKind kind : OreKind.values()) {
            if (marks.picked(kind)) {
                picked |= 1 << kind.ordinal();
            }
        }
        return picked;
    }

    private long bestProbe(long suspect, Vec3 eye, double reach) {
        int x = BlockPos.getX(suspect);
        int y = BlockPos.getY(suspect);
        int z = BlockPos.getZ(suspect);
        long best = NONE;
        int bestCount = 0;
        for (int[] step : AROUND) {
            int probeX = x - step[0];
            int probeY = y - step[1];
            int probeZ = z - step[2];
            if (boxDistanceSqr(eye, probeX, probeY, probeZ) >= reach * reach
                || mc.level.isOutsideBuildHeight(probeY)) {
                continue;
            }
            int count = 0;
            for (int[] around : AROUND) {
                long near = BlockPos.asLong(probeX + around[0], probeY + around[1], probeZ + around[2]);
                if (suspected.contains(near) && !isAsked(near)) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = BlockPos.asLong(probeX, probeY, probeZ);
            }
        }
        return best;
    }

    // Squared distance from the eyes to the nearest point of a block as the server measures it.
    private static double boxDistanceSqr(Vec3 eye, int x, int y, int z) {
        double dx = Math.max(Math.max(x - eye.x, eye.x - (x + 1)), 0);
        double dy = Math.max(Math.max(y - eye.y, eye.y - (y + 1)), 0);
        double dz = Math.max(Math.max(z - eye.z, eye.z - (z + 1)), 0);
        return dx * dx + dy * dy + dz * dz;
    }

    private void send(long at) {
        BlockPos pos = BlockPos.of(at);
        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.CHANGE_DESTROY_DIRECTION, pos, BlockUtil.facingSide(pos)));
        for (int[] step : AROUND) {
            markAsked(pos.getX() + step[0], pos.getY() + step[1], pos.getZ() + step[2]);
        }
    }

    private boolean isAsked(long key) {
        int x = BlockPos.getX(key);
        int y = BlockPos.getY(key);
        int z = BlockPos.getZ(key);
        BitSet cells = asked.get(sectionOf(x, y, z));
        return cells != null && cells.get(cellOf(x, y, z));
    }

    private void markAsked(int x, int y, int z) {
        asked.computeIfAbsent(sectionOf(x, y, z), key -> new BitSet()).set(cellOf(x, y, z));
    }

    private static long sectionOf(int x, int y, int z) {
        return SectionPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y),
            SectionPos.blockToSectionCoord(z));
    }

    private static int cellOf(int x, int y, int z) {
        return (y & SectionPos.SECTION_MASK) << 8 | (z & SectionPos.SECTION_MASK) << 4 | x & SectionPos.SECTION_MASK;
    }

    // A section that arrived before the module was on is read when first needed.
    private int hidesAt(BlockPos pos) {
        long key = sectionOf(pos.getX(), pos.getY(), pos.getZ());
        if (hides.containsKey(key)) {
            return hides.get(key);
        }
        LevelChunk chunk = mc.level.getChunkAt(pos);
        int kinds = readSection(chunk.getSection(chunk.getSectionIndex(pos.getY())),
            SectionPos.blockToSectionCoord(pos.getY()));
        hides.put(key, kinds);
        return kinds;
    }

    private int readSection(LevelChunkSection section, int sectionY) {
        Reading reading = read(section);
        if (!judged && reading.listing() && SectionPos.sectionToBlockCoord(sectionY) < AntiXray.HIDDEN_BELOW) {
            listing++;
            marked += reading.marked() ? 1 : 0;
        }
        return reading.kinds();
    }

    // Paper swaps a buried block for a filler and leaves its palette entry unused. A section
    // with such an entry may hide any ore its palette lists. In its other modes every low
    // section lists the whole hidden list.
    private static Reading read(LevelChunkSection section) {
        Palette<BlockState> palette = PaletteCells.listedBlocks(section);
        if (palette == null) {
            return section.getStates().data.storage().getBits() == 0 ? NOTHING : UNLISTED;
        }
        Set<Block> listed = new HashSet<>();
        int kinds = 0;
        for (int id = 0; id < palette.getSize(); id++) {
            Block block = palette.valueFor(id).getBlock();
            if (AntiXray.HIDDEN.contains(block)) {
                listed.add(block);
                OreKind kind = OreMarks.kindOf(block);
                kinds |= kind == null ? 0 : 1 << kind.ordinal();
            }
        }
        if (listed.isEmpty()) {
            return NOTHING;
        }
        if (listed.size() >= LISTED_IN_FULL) {
            return new Reading(EVERY_KIND, true, true);
        }
        boolean[] used = PaletteCells.usedBlocks(section);
        for (int id = 0; id < used.length; id++) {
            if (!used[id] && AntiXray.HIDDEN.contains(palette.valueFor(id).getBlock())) {
                return new Reading(kinds, true, true);
            }
        }
        return new Reading(0, false, true);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        marks.draw(event.getBatch());
    }

    private static int[][] around() {
        List<int[]> steps = new ArrayList<>();
        for (int dx = -SPREAD; dx <= SPREAD; dx++) {
            for (int dy = -SPREAD; dy <= SPREAD; dy++) {
                for (int dz = -SPREAD; dz <= SPREAD; dz++) {
                    int length = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (length >= 1 && length <= SPREAD) {
                        steps.add(new int[] {dx, dy, dz});
                    }
                }
            }
        }
        return steps.toArray(int[][]::new);
    }

    private static int[][] near(int radius) {
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                        offsets.add(new int[] {dx, dy, dz});
                    }
                }
            }
        }
        offsets.sort(Comparator.comparingInt(offset -> offset[0] * offset[0] + offset[1] * offset[1]
            + offset[2] * offset[2]));
        return offsets.toArray(int[][]::new);
    }
}
