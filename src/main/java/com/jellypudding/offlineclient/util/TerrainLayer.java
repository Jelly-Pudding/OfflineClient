package com.jellypudding.offlineclient.util;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.util.Arrays;
import java.util.BitSet;

// A tile holds the packed map colour of every block of a chunk and tiles are kept in square
// regions. Each region also keeps one colour for each chunk and a wide view reads those. A stored
// tile is never changed and a new one takes its place. Only the regions a view shows block by block
// keep their tiles. Game thread only.
public final class TerrainLayer {

    // The side of a region in chunks as a power of two.
    private static final int REGION_SHIFT = 5;
    private static final int REGION_SIDE = 1 << REGION_SHIFT;
    public static final int TILES = REGION_SIDE * REGION_SIDE;
    public static final int TILE_SIZE = SectionPos.SECTION_SIZE * SectionPos.SECTION_SIZE;
    // The packed colour of land that holds nothing with a colour of its own such as the void. A
    // map colour of none reaches a tile no other way.
    public static final byte VOID = 1;

    private static final int CHUNK_MASK = REGION_SIDE - 1;
    private static final int REGION_BLOCK_SHIFT = SectionPos.SECTION_BITS + REGION_SHIFT;
    // The block whose colour stands for its whole chunk in a wide view.
    private static final int MIDDLE = cell(SectionPos.SECTION_HALF_SIZE, SectionPos.SECTION_HALF_SIZE);
    // A region last asked for this long ago has left every view and its file is not read.
    private static final long STALE_WANT_MS = 1000;

    private static final class Region {
        private final byte[][] tiles = new byte[TILES][];
        private final byte[] overview = new byte[TILES];
        // The tiles read this session win over a file. The rest wait to be written.
        private final BitSet seen = new BitSet(TILES);
        private final BitSet unsaved = new BitSet(TILES);
        // The saved file holds tiles the region has not taken in.
        private boolean filed;
        private boolean reading;
        private boolean holdsTiles;
        private long wantedAt;

        private void set(int index, byte[] tile) {
            tiles[index] = tile;
            overview[index] = tile[MIDDLE];
            holdsTiles = true;
        }
    }

    private final Long2ObjectOpenHashMap<Region> regions = new Long2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet wanted = new LongLinkedOpenHashSet();
    private int revision;
    // The region the last read landed in. Reads across a picture mostly stay in one.
    private Region last;
    private int lastX;
    private int lastZ;
    private boolean cached;

    // Goes up whenever a tile changes. A picture of the layer is painted again only when this moves.
    public int revision() {
        return revision;
    }

    // Nought where no chunk was read. A coarse read and a chunk whose tile is not held give the
    // colour that stands for the whole chunk.
    public int packed(int x, int z, boolean coarse) {
        int regionX = x >> REGION_BLOCK_SHIFT;
        int regionZ = z >> REGION_BLOCK_SHIFT;
        if (!cached || regionX != lastX || regionZ != lastZ) {
            last = regions.get(ChunkPos.pack(regionX, regionZ));
            lastX = regionX;
            lastZ = regionZ;
            cached = true;
        }
        if (last == null) {
            return 0;
        }
        int index = index(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        byte[] tile = coarse ? null : last.tiles[index];
        return (tile == null ? last.overview[index] : tile[cell(x, z)]) & 0xFF;
    }

    // A view that shows each block asks every frame.
    public void want(int minX, int minZ, int maxX, int maxZ) {
        long now = System.currentTimeMillis();
        for (int regionZ = minZ >> REGION_BLOCK_SHIFT; regionZ <= maxZ >> REGION_BLOCK_SHIFT; regionZ++) {
            for (int regionX = minX >> REGION_BLOCK_SHIFT; regionX <= maxX >> REGION_BLOCK_SHIFT; regionX++) {
                long key = ChunkPos.pack(regionX, regionZ);
                Region region = regions.get(key);
                if (region == null) {
                    continue;
                }
                region.wantedAt = now;
                if (region.filed && !region.reading) {
                    wanted.add(key);
                }
            }
        }
    }

    void put(int chunkX, int chunkZ, byte[] tile) {
        Region region = region(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);
        int index = index(chunkX, chunkZ);
        region.seen.set(index);
        if (Arrays.equals(region.tiles[index], tile)) {
            return;
        }
        region.set(index, tile);
        region.unsaved.set(index);
        revision++;
    }

    void absorbOverviews(Long2ObjectMap<byte[]> overviews) {
        boolean changed = false;
        for (Long2ObjectMap.Entry<byte[]> entry : Long2ObjectMaps.fastIterable(overviews)) {
            long key = entry.getLongKey();
            Region region = region(ChunkPos.getX(key), ChunkPos.getZ(key));
            changed |= takeOverview(region, entry.getValue());
            region.filed = true;
        }
        if (changed) {
            revision++;
        }
    }

    // Each region counts as being read until its answer comes back.
    LongList takeWanted(int most) {
        long now = System.currentTimeMillis();
        LongList taken = new LongArrayList();
        while (taken.size() < most && !wanted.isEmpty()) {
            long key = wanted.removeFirstLong();
            Region region = regions.get(key);
            if (region != null && region.filed && !region.reading && now - region.wantedAt < STALE_WANT_MS) {
                region.reading = true;
                taken.add(key);
            }
        }
        return taken;
    }

    // Null tiles mean the file could not be read. Tiles read this session stay as they are.
    void absorbRead(long key, byte[][] tiles) {
        Region region = regions.get(key);
        if (region == null) {
            return;
        }
        region.reading = false;
        region.filed = false;
        if (tiles != null && takeTiles(region, tiles)) {
            revision++;
        }
    }

    void absorbSaved(Long2ObjectMap<byte[][]> saved) {
        boolean changed = false;
        for (Long2ObjectMap.Entry<byte[][]> entry : Long2ObjectMaps.fastIterable(saved)) {
            Region region = regions.get(entry.getLongKey());
            if (region != null) {
                changed |= region.filed ? takeOverview(region, overviewOf(entry.getValue()))
                    : takeTiles(region, entry.getValue());
            }
        }
        if (changed) {
            revision++;
        }
    }

    private static boolean takeOverview(Region region, byte[] overview) {
        boolean changed = false;
        for (int index = 0; index < TILES; index++) {
            if (overview[index] != 0 && !region.seen.get(index) && region.overview[index] != overview[index]) {
                region.overview[index] = overview[index];
                changed = true;
            }
        }
        return changed;
    }

    private static boolean takeTiles(Region region, byte[][] tiles) {
        boolean changed = false;
        for (int index = 0; index < TILES; index++) {
            byte[] tile = tiles[index];
            if (tile != null && !region.seen.get(index) && !Arrays.equals(tile, region.tiles[index])) {
                region.set(index, tile);
                changed = true;
            }
        }
        return changed;
    }

    // The tiles count as written from here on.
    Long2ObjectMap<byte[][]> takeUnsaved() {
        Long2ObjectMap<byte[][]> taken = new Long2ObjectOpenHashMap<>();
        Long2ObjectMaps.fastForEach(regions, entry -> {
            Region region = entry.getValue();
            if (region.unsaved.isEmpty()) {
                return;
            }
            byte[][] tiles = new byte[TILES][];
            region.unsaved.stream().forEach(index -> tiles[index] = region.tiles[index]);
            region.unsaved.clear();
            taken.put(entry.getLongKey(), tiles);
        });
        return taken;
    }

    // Tiles a write could not save wait again. A region that let them go meanwhile takes them back.
    void giveBack(Long2ObjectMap<byte[][]> taken) {
        boolean changed = false;
        for (Long2ObjectMap.Entry<byte[][]> entry : Long2ObjectMaps.fastIterable(taken)) {
            Region region = regions.get(entry.getLongKey());
            byte[][] tiles = entry.getValue();
            for (int index = 0; region != null && index < TILES; index++) {
                if (tiles[index] == null) {
                    continue;
                }
                if (!region.seen.get(index)) {
                    region.set(index, tiles[index]);
                    changed = true;
                }
                region.seen.set(index);
                region.unsaved.set(index);
            }
        }
        if (changed) {
            revision++;
        }
    }

    void unsaveSeen() {
        regions.values().forEach(region -> region.unsaved.or(region.seen));
    }

    // The colour of each chunk stays and the file gives the tiles back.
    void letGo(long idleMs) {
        long now = System.currentTimeMillis();
        for (Region region : regions.values()) {
            if (region.holdsTiles && !region.reading && region.unsaved.isEmpty()
                && now - region.wantedAt > idleMs) {
                Arrays.fill(region.tiles, null);
                region.seen.clear();
                region.holdsTiles = false;
                region.filed = true;
            }
        }
    }

    void keepNear(int chunkX, int chunkZ, int radius) {
        boolean changed = false;
        for (ObjectIterator<Long2ObjectMap.Entry<Region>> regionsLeft = regions.long2ObjectEntrySet().fastIterator();
             regionsLeft.hasNext(); ) {
            Long2ObjectMap.Entry<Region> entry = regionsLeft.next();
            Region region = entry.getValue();
            int baseX = ChunkPos.getX(entry.getLongKey()) << REGION_SHIFT;
            int baseZ = ChunkPos.getZ(entry.getLongKey()) << REGION_SHIFT;
            boolean empty = true;
            for (int index = 0; index < TILES; index++) {
                if (region.tiles[index] == null) {
                    continue;
                }
                int x = baseX + (index & CHUNK_MASK);
                int z = baseZ + (index >> REGION_SHIFT);
                if (Mth.chessboardDistance(x, z, chunkX, chunkZ) <= radius) {
                    empty = false;
                    continue;
                }
                region.tiles[index] = null;
                region.overview[index] = 0;
                region.seen.clear(index);
                region.unsaved.clear(index);
                changed = true;
            }
            if (empty) {
                regionsLeft.remove();
            }
        }
        cached = false;
        if (changed) {
            revision++;
        }
    }

    void clear() {
        if (!regions.isEmpty()) {
            regions.clear();
            revision++;
        }
        wanted.clear();
        cached = false;
    }

    public static byte[] overviewOf(byte[][] tiles) {
        byte[] overview = new byte[TILES];
        for (int index = 0; index < TILES; index++) {
            if (tiles[index] != null) {
                overview[index] = tiles[index][MIDDLE];
            }
        }
        return overview;
    }

    private Region region(int regionX, int regionZ) {
        long key = ChunkPos.pack(regionX, regionZ);
        Region region = regions.get(key);
        if (region == null) {
            region = new Region();
            regions.put(key, region);
            cached = false;
        }
        return region;
    }

    private static int index(int chunkX, int chunkZ) {
        return (chunkZ & CHUNK_MASK) << REGION_SHIFT | (chunkX & CHUNK_MASK);
    }

    static int cell(int x, int z) {
        return SectionPos.sectionRelative(z) * SectionPos.SECTION_SIZE + SectionPos.sectionRelative(x);
    }
}
