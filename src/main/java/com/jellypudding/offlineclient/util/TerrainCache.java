package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.SharedFiles;
import com.jellypudding.offlineclient.config.TerrainFiles;
import com.jellypudding.offlineclient.setting.BoolSetting;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// The land of every chunk you have seen on the server you play on as map tiles. Each dimension
// keeps its own and a tile is read as its chunk arrives and again when the server changes a block
// in it. With saving on the tiles sit in files per server and come back on the next visit. A visit
// reads the colour of each saved chunk and the tiles of a region only once a view needs them. Saved
// tiles no view needs are let go. A second layer holds the land at your own height round you for a
// view under a roof. Game thread only apart from recording changed blocks.
public final class TerrainCache {

    // How long each tick may spend reading chunks in nanoseconds. The rest wait their turn. A
    // chunk under the Nether roof takes far longer than one in the open.
    private static final long READ_NANOS = 3_000_000;
    private static final long CAVE_READ_NANOS = 2_000_000;
    // Tiles wait this long after the last write before they are written.
    private static final long WRITE_GAP_MS = 10_000;
    // The tiles of the land this many blocks round you stay whatever the map shows.
    private static final int KEEP_AROUND = 1024;
    // Saved tiles no view needed for this long are let go.
    private static final long IDLE_MS = 30_000;
    // Region files read at once. Every other saved file of the client waits behind these.
    private static final int MOST_READS = 8;
    // Sky light this weak at your head means a roof is overhead. Leaves and shallow water let
    // more through.
    private static final int ROOF_LIGHT = 8;

    // Every cache. The game saves them all as it closes.
    private static final List<TerrainCache> ALL = new CopyOnWriteArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(TerrainCache::saveAllOnExit, "OfflineClient map save"));
    }

    private final BoolSetting save;
    private final TerrainColumns columns = new TerrainColumns();
    // The tiles of the server you play on by dimension and the dimensions whose files were read.
    private final Map<String, TerrainLayer> layers = new HashMap<>();
    private final Set<String> filesAsked = new HashSet<>();
    private final TerrainLayer cave = new TerrainLayer();
    // Chunks waiting to be read. Oldest first.
    private final LongLinkedOpenHashSet waiting = new LongLinkedOpenHashSet();
    // Chunks whose first row was shaded flat because the chunk north of them had not arrived.
    private final LongOpenHashSet flatNorth = new LongOpenHashSet();
    // Chunks the server changed. Filled from the network thread. The game applies a change after
    // it is seen and the chunk is read on the tick after.
    private final Set<Long> changed = ConcurrentHashMap.newKeySet();
    private LongOpenHashSet due = new LongOpenHashSet();
    // The chunks of the layer round you read at its height.
    private final LongOpenHashSet caveRead = new LongOpenHashSet();
    private int caveY = TerrainColumns.UNKNOWN;
    private long caveCentre = ChunkPos.INVALID_CHUNK_POS;
    private int caveRadius;
    // A rejoin and a dimension change both hand out a new world.
    private final WorldWatch world = new WorldWatch();
    private TerrainLayer here;
    private String hereDimension;
    // The server the tiles belong to. Null before the first world.
    private String server;
    // Moves on with every new server. An answer that comes back for an older one is dropped.
    private int generation;
    private boolean saving;
    private long lastWrite;
    // Region files asked for that have not answered yet.
    private int reading;

    // The switch that keeps the tiles in files.
    public TerrainCache(BoolSetting save) {
        this.save = save;
        ALL.add(this);
    }

    // The tiles of the dimension you are in. Null before the first tick in a world.
    public TerrainLayer here() {
        return here;
    }

    // The land at your own height round you. Empty whilst nothing asks for it.
    public TerrainLayer cave() {
        return cave;
    }

    // Reads every chunk already loaded on the next tick.
    public void start() {
        world.forget();
    }

    // Writes what waits and lets go of what was queued. Saved tiles go at once.
    public void stop() {
        waiting.clear();
        changed.clear();
        due.clear();
        clearCave();
        writeDue(true);
        letGo(0);
    }

    public void arrived(LevelChunk chunk) {
        waiting.add(chunk.getPos().pack());
    }

    // Safe from any thread.
    public void noteChange(Packet<?> packet) {
        switch (packet) {
            case ClientboundBlockUpdatePacket update -> changedAt(update.getPos());
            case ClientboundSectionBlocksUpdatePacket update -> update.runUpdates((pos, state) -> changedAt(pos));
            default -> {
            }
        }
    }

    // A block on the last row of a chunk also shades the first row of the chunk south of it.
    private void changedAt(BlockPos pos) {
        int x = SectionPos.blockToSectionCoord(pos.getX());
        int z = SectionPos.blockToSectionCoord(pos.getZ());
        changed.add(ChunkPos.pack(x, z));
        if (SectionPos.sectionRelative(pos.getZ()) == SectionPos.SECTION_MAX_INDEX) {
            changed.add(ChunkPos.pack(x, z + 1));
        }
    }

    // Once a client tick. A radius above nought keeps that many chunks of the layer round you
    // up to date.
    public void tick(int caveChunks) {
        ClientLevel level = OfflineClient.MC.level;
        LocalPlayer player = OfflineClient.MC.player;
        if (level == null || player == null) {
            writeDue(false);
            return;
        }
        follow();
        if (world.changed()) {
            enter();
        }
        syncSaving();
        takeChanges();
        readWaiting(level);
        keepCave(level, player, caveChunks);
        here.want(player.getBlockX() - KEEP_AROUND, player.getBlockZ() - KEEP_AROUND,
            player.getBlockX() + KEEP_AROUND, player.getBlockZ() + KEEP_AROUND);
        readWanted();
        writeDue(false);
        letGo(IDLE_MS);
    }

    // The height to stand at in a column of the land the map shows. Under trees that is the
    // ground. Your own height where the chunk is not loaded or holds nothing to stand on.
    public int standY(int x, int z) {
        ClientLevel level = OfflineClient.MC.level;
        LocalPlayer player = OfflineClient.MC.player;
        LevelChunk chunk = loaded(level, SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        if (chunk == null) {
            return player.getBlockY();
        }
        int y = level.dimensionType().hasCeiling() ? columns.floorY(chunk, x, z)
            : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
        return y == TerrainColumns.UNKNOWN || y <= level.getMinY() ? player.getBlockY() : y;
    }

    // True whilst something overhead keeps the sky from you. Where the sky gives no light a roof
    // is any block above your head.
    public static boolean underRoof(ClientLevel level, LocalPlayer player) {
        BlockPos head = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
        if (level.dimensionType().hasSkyLight()) {
            return level.getBrightness(LightLayer.SKY, head) < ROOF_LIGHT;
        }
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, head.getX(), head.getZ()) > head.getY();
    }

    // The tiles follow the server you play on. A new one writes what the last was waiting for and
    // starts again from its own files.
    private void follow() {
        String now = ServerInfo.key();
        if (now.equals(server)) {
            return;
        }
        writeDue(true);
        server = now;
        generation++;
        layers.clear();
        filesAsked.clear();
        here = null;
        world.forget();
    }

    // A new world. Its chunks may have arrived before this tick and are read with the rest.
    private void enter() {
        hereDimension = ServerInfo.dimension();
        here = layers.computeIfAbsent(hereDimension, _ -> new TerrainLayer());
        if (saving) {
            load(hereDimension, here);
        }
        clearCave();
        flatNorth.clear();
        LoadedChunks.forEach(chunk -> waiting.add(chunk.getPos().pack()));
    }

    // Switched on part way through it reads the files and writes every tile of this visit.
    private void syncSaving() {
        if (save.isOn() == saving) {
            return;
        }
        saving = save.isOn();
        if (saving) {
            layers.forEach((dimension, layer) -> {
                layer.unsaveSeen();
                load(dimension, layer);
            });
        }
    }

    // Asks once a visit for the colour of each chunk earlier visits saved of a dimension.
    private void load(String dimension, TerrainLayer layer) {
        if (!filesAsked.add(dimension)) {
            return;
        }
        Path folder = TerrainFiles.folder(server, dimension);
        int asked = generation;
        SharedFiles.submit(() -> TerrainFiles.readOverviews(folder), overviews -> {
            if (overviews != null && asked == generation) {
                layer.absorbOverviews(overviews);
            }
        });
    }

    // Reads the tiles of the regions a view needs a few files at a time.
    private void readWanted() {
        LongList regions = here.takeWanted(MOST_READS - reading);
        if (regions.isEmpty()) {
            return;
        }
        Path folder = TerrainFiles.folder(server, hereDimension);
        TerrainLayer layer = here;
        int asked = generation;
        for (int i = 0; i < regions.size(); i++) {
            long region = regions.getLong(i);
            reading++;
            SharedFiles.submit(() -> TerrainFiles.readRegion(folder, region), tiles -> {
                reading--;
                if (asked == generation) {
                    layer.absorbRead(region, tiles);
                }
            });
        }
    }

    // Saved tiles no view needed for this long are let go. Without saving every tile stays.
    private void letGo(long idleMs) {
        if (saving) {
            layers.values().forEach(layer -> layer.letGo(idleMs));
        }
    }

    private void takeChanges() {
        LongOpenHashSet ready = due;
        due = new LongOpenHashSet();
        for (Iterator<Long> keys = changed.iterator(); keys.hasNext(); ) {
            due.add(keys.next().longValue());
            keys.remove();
        }
        for (LongIterator keys = ready.iterator(); keys.hasNext(); ) {
            long key = keys.nextLong();
            waiting.addAndMoveToFirst(key);
            caveRead.remove(key);
        }
    }

    private void readWaiting(ClientLevel level) {
        boolean roofed = level.dimensionType().hasCeiling();
        long until = System.nanoTime() + READ_NANOS;
        while (!waiting.isEmpty() && System.nanoTime() < until) {
            long key = waiting.removeFirstLong();
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            LevelChunk chunk = loaded(level, x, z);
            if (chunk == null) {
                continue;
            }
            LevelChunk north = loaded(level, x, z - 1);
            here.put(x, z, columns.surface(chunk, north, roofed));
            if (north == null) {
                flatNorth.add(key);
            } else {
                flatNorth.remove(key);
            }
            long south = ChunkPos.pack(x, z + 1);
            if (flatNorth.remove(south) && loaded(level, x, z + 1) != null) {
                waiting.add(south);
            }
        }
    }

    // Reads the land at your height round you nearest first. Rising or dropping a block starts it
    // again and the chunks the server changes are read again.
    private void keepCave(ClientLevel level, LocalPlayer player, int radius) {
        if (radius <= 0) {
            clearCave();
            return;
        }
        int headY = Mth.floor(player.getEyeY());
        int centreX = player.chunkPosition().x();
        int centreZ = player.chunkPosition().z();
        long centre = ChunkPos.pack(centreX, centreZ);
        if (headY != caveY) {
            caveY = headY;
            caveRead.clear();
        }
        if (centre != caveCentre || radius != caveRadius) {
            caveCentre = centre;
            caveRadius = radius;
            cave.keepNear(centreX, centreZ, radius);
            for (LongIterator keys = caveRead.iterator(); keys.hasNext(); ) {
                long key = keys.nextLong();
                if (Mth.chessboardDistance(ChunkPos.getX(key), ChunkPos.getZ(key), centreX, centreZ) > radius) {
                    keys.remove();
                }
            }
        }
        long until = System.nanoTime() + CAVE_READ_NANOS;
        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                // Inside a ring only its top and bottom edge are new.
                int step = Math.abs(dx) == ring ? 1 : 2 * ring;
                for (int dz = -ring; dz <= ring; dz += step) {
                    if (System.nanoTime() >= until) {
                        return;
                    }
                    int x = centreX + dx;
                    int z = centreZ + dz;
                    LevelChunk chunk = loaded(level, x, z);
                    if (chunk != null && caveRead.add(ChunkPos.pack(x, z))) {
                        cave.put(x, z, columns.layer(chunk, loaded(level, x, z - 1), headY));
                    }
                }
            }
        }
    }

    private void clearCave() {
        cave.clear();
        caveRead.clear();
        caveY = TerrainColumns.UNKNOWN;
        caveCentre = ChunkPos.INVALID_CHUNK_POS;
    }

    private static LevelChunk loaded(ClientLevel level, int chunkX, int chunkZ) {
        return level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
    }

    // Writes the tiles that wait once the pause after the last write has passed. Leaving a server
    // writes them at once.
    private void writeDue(boolean now) {
        long time = System.currentTimeMillis();
        if (!saving || server == null || !now && time - lastWrite < WRITE_GAP_MS) {
            return;
        }
        lastWrite = time;
        layers.forEach(this::write);
    }

    private void write(String dimension, TerrainLayer layer) {
        Long2ObjectMap<byte[][]> unsaved = layer.takeUnsaved();
        if (unsaved.isEmpty()) {
            return;
        }
        Path folder = TerrainFiles.folder(server, dimension);
        int asked = generation;
        SharedFiles.submit(() -> SharedFiles.locked(() -> TerrainFiles.merge(folder, unsaved)), saved -> {
            if (asked != generation) {
                return;
            }
            if (saved == null) {
                // Tried again after the usual pause.
                layer.giveBack(unsaved);
                return;
            }
            layer.absorbSaved(saved);
        });
    }

    // Writes what every cache still holds unwritten as the game closes. The writes queue behind
    // whatever the file thread still has to do and the game waits for all of them.
    private static void saveAllOnExit() {
        List<Runnable> pending = new ArrayList<>();
        for (TerrainCache cache : ALL) {
            if (!cache.saving || cache.server == null) {
                continue;
            }
            cache.layers.forEach((dimension, layer) -> {
                Long2ObjectMap<byte[][]> unsaved = layer.takeUnsaved();
                Path folder = TerrainFiles.folder(cache.server, dimension);
                if (!unsaved.isEmpty()) {
                    pending.add(() -> {
                        try {
                            SharedFiles.locked(() -> TerrainFiles.merge(folder, unsaved));
                        } catch (IOException | RuntimeException e) {
                            OfflineClient.LOG.error("Could not save the map of {}", dimension, e);
                        }
                    });
                }
            });
        }
        if (!pending.isEmpty()) {
            SharedFiles.await(() -> {
                pending.forEach(Runnable::run);
                return null;
            });
        }
    }
}
