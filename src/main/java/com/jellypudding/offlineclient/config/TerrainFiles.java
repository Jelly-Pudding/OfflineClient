package com.jellypudding.offlineclient.config;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.TerrainLayer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.ChunkPos;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

// The saved map tiles of a server. Each dimension has a folder and each region of chunks a small
// packed file in it. One more file holds the colour that stands for each chunk. A visit reads that
// until a view needs the tiles of a region. A write lays this game's tiles over what the file holds
// and two games on this computer keep what both of them saw. Only the file thread calls it.
public final class TerrainFiles {

    private static final String FOLDER = "maps";
    private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.map");
    private static final String OVERVIEW = "overview.map";
    // Goes up whenever the layout of the files changes. A file of another layout reads as empty
    // and the chunk colours are gathered from the regions again.
    private static final int LAYOUT = 1;

    // The colours the overview file holds and the regions it is behind on. Those are the region
    // files it misses or that were written after it and the regions whose file is gone.
    private record Overview(Long2ObjectMap<byte[]> colours, LongList behind) {
    }

    private TerrainFiles() {
    }

    public static Path folder(String server, String dimension) {
        return DataFiles.path(FOLDER, DataFiles.keyName(server), DataFiles.safeName(dimension));
    }

    // The colour of every chunk saved in the folder by region. Each region file the overview file
    // is behind on is read for its colours and the overview file written again.
    public static Long2ObjectMap<byte[]> readOverviews(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return new Long2ObjectOpenHashMap<>();
        }
        Overview found = overview(folder);
        if (found.behind().isEmpty()) {
            return found.colours();
        }
        return SharedFiles.locked(() -> {
            Overview current = overview(folder);
            if (catchUp(folder, current)) {
                // A failed write was logged and the next visit catches up again.
                saveOverviews(folder, current.colours());
            }
            return current.colours();
        });
    }

    // The tiles of one region. Null for a file that is missing or cannot be read.
    public static byte[][] readRegion(Path folder, long region) {
        return read(regionFile(folder, region));
    }

    // Lays the tiles over each saved region and writes it back with the colours of its chunks.
    // Hands back every region as the write left its file. Only call it whilst the shared lock is held.
    public static Long2ObjectMap<byte[][]> merge(Path folder, Long2ObjectMap<byte[][]> mine) throws IOException {
        // An overview file that cannot be read starts empty and the next visit catches up on the rest.
        Long2ObjectMap<byte[]> overviews = Objects.requireNonNullElseGet(readOverviewFile(folder),
            Long2ObjectOpenHashMap::new);
        Long2ObjectMap<byte[][]> saved = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<byte[][]> entry : mine.long2ObjectEntrySet()) {
            long region = entry.getLongKey();
            Path file = regionFile(folder, region);
            // A broken file is written over with what this game holds.
            byte[][] tiles = Objects.requireNonNullElseGet(read(file), () -> new byte[TerrainLayer.TILES][]);
            byte[][] ours = entry.getValue();
            for (int index = 0; index < TerrainLayer.TILES; index++) {
                if (ours[index] != null) {
                    tiles[index] = ours[index];
                }
            }
            if (!DataFiles.writeSafely(file, temp -> write(temp, tiles))) {
                throw new IOException("Could not write " + file.getFileName());
            }
            overviews.put(region, TerrainLayer.overviewOf(tiles));
            saved.put(region, tiles);
        }
        if (!saveOverviews(folder, overviews)) {
            throw new IOException("Could not write " + OVERVIEW);
        }
        return saved;
    }

    private static Path regionFile(Path folder, long region) {
        return folder.resolve("r." + ChunkPos.getX(region) + "." + ChunkPos.getZ(region) + ".map");
    }

    // Reads the overview file and checks it against the region files of the folder.
    private static Overview overview(Path folder) throws IOException {
        // Taken before the colours are read. A write between the two only adds to what is behind.
        SharedFiles.Stamp written = SharedFiles.stamp(folder.resolve(OVERVIEW));
        Long2ObjectMap<byte[]> colours = Objects.requireNonNullElseGet(readOverviewFile(folder),
            Long2ObjectOpenHashMap::new);
        LongSet gone = new LongOpenHashSet(colours.keySet());
        LongList behind = new LongArrayList();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path file : files) {
                Matcher name = NAME.matcher(file.getFileName().toString());
                if (!name.matches()) {
                    continue;
                }
                long region;
                try {
                    region = ChunkPos.pack(Integer.parseInt(name.group(1)), Integer.parseInt(name.group(2)));
                } catch (NumberFormatException e) {
                    // A number too long for a region is no file of ours.
                    continue;
                }
                gone.remove(region);
                SharedFiles.Stamp stamp = SharedFiles.stamp(file);
                if (stamp == null || written == null || !colours.containsKey(region)
                    || stamp.modified().compareTo(written.modified()) > 0) {
                    behind.add(region);
                }
            }
        }
        behind.addAll(gone);
        return new Overview(colours, behind);
    }

    // Reads the colours of each region the overview is behind on from its file. A region whose
    // file is gone or cannot be read leaves the overview. True when any colour changed.
    private static boolean catchUp(Path folder, Overview overview) {
        boolean changed = false;
        for (int i = 0; i < overview.behind().size(); i++) {
            long region = overview.behind().getLong(i);
            byte[][] tiles = readRegion(folder, region);
            byte[] colours = tiles == null ? null : TerrainLayer.overviewOf(tiles);
            byte[] was = colours == null ? overview.colours().remove(region)
                : overview.colours().put(region, colours);
            changed |= !Arrays.equals(was, colours);
        }
        return changed;
    }

    // Null for a file that is missing or cannot be read.
    private static Long2ObjectMap<byte[]> readOverviewFile(Path folder) {
        Path file = folder.resolve(OVERVIEW);
        if (!Files.exists(file)) {
            return null;
        }
        try (DataInputStream in = open(file)) {
            if (in.readInt() != LAYOUT) {
                return null;
            }
            Long2ObjectMap<byte[]> overviews = new Long2ObjectOpenHashMap<>();
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int regionX = in.readInt();
                int regionZ = in.readInt();
                byte[] overview = new byte[TerrainLayer.TILES];
                in.readFully(overview);
                overviews.put(ChunkPos.pack(regionX, regionZ), overview);
            }
            return overviews;
        } catch (IOException e) {
            OfflineClient.LOG.warn("Cannot read the map file {}", file.getFileName(), e);
            return null;
        }
    }

    // The layout followed by how many regions there are and then each one after its place. False
    // after logging why the file could not be written.
    private static boolean saveOverviews(Path folder, Long2ObjectMap<byte[]> overviews) {
        return DataFiles.writeSafely(folder.resolve(OVERVIEW), temp -> {
            try (DataOutputStream out = create(temp)) {
                out.writeInt(LAYOUT);
                out.writeInt(overviews.size());
                for (Long2ObjectMap.Entry<byte[]> entry : Long2ObjectMaps.fastIterable(overviews)) {
                    out.writeInt(ChunkPos.getX(entry.getLongKey()));
                    out.writeInt(ChunkPos.getZ(entry.getLongKey()));
                    out.write(entry.getValue());
                }
            }
        });
    }

    // Null for a file that is missing or cannot be read.
    private static byte[][] read(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try (DataInputStream in = open(file)) {
            if (in.readInt() != LAYOUT) {
                return null;
            }
            byte[][] tiles = new byte[TerrainLayer.TILES][];
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                int index = in.readUnsignedShort();
                byte[] tile = new byte[TerrainLayer.TILE_SIZE];
                in.readFully(tile);
                if (index < TerrainLayer.TILES) {
                    tiles[index] = tile;
                }
            }
            return tiles;
        } catch (IOException e) {
            OfflineClient.LOG.warn("Cannot read the map file {}", file.getFileName(), e);
            return null;
        }
    }

    // The layout followed by how many tiles there are and then each one after its place.
    private static void write(Path file, byte[][] tiles) throws IOException {
        int count = 0;
        for (byte[] tile : tiles) {
            if (tile != null) {
                count++;
            }
        }
        try (DataOutputStream out = create(file)) {
            out.writeInt(LAYOUT);
            out.writeShort(count);
            for (int index = 0; index < tiles.length; index++) {
                if (tiles[index] != null) {
                    out.writeShort(index);
                    out.write(tiles[index]);
                }
            }
        }
    }

    private static DataInputStream open(Path file) throws IOException {
        return new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))));
    }

    private static DataOutputStream create(Path file) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file))));
    }
}
