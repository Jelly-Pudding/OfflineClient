package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.FindLog.Find;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// How the finds of a log sit in a file and how two copies of them come back together. The
// finds are held by dimension and then by the key of their spot. Each dimension runs oldest
// first. One of these follows the file of one log on one server and remembers what the file
// held when this game last read or wrote it. Only the shared file thread touches it and that
// thread takes its work in order. A write therefore sees what the write before it did.
final class FindFile {

    // What the file held after a read or a write. News is false when it held nothing the game
    // had not taken in already. The finds are null for a file that could not be read.
    record Reading(Map<String, Map<Long, Find>> finds, boolean news) {
    }

    // A file that cannot be read is kept beside the new one under this ending.
    private static final String BROKEN = ".broken";
    // Spreadsheets read the file as UTF 8 when it starts with this mark.
    private static final String BYTE_ORDER_MARK = "\uFEFF";

    private final Path file;
    private final boolean byChunk;
    // What the file held at the last read or write and its stamp then. No stamp and no finds
    // stands for a missing file. Null finds stand for a file that could not be read.
    private Map<String, Map<Long, Find>> held = Map.of();
    private SharedFiles.Stamp stamp;
    // The number of the last write of this game that reached the file and the finds it carried.
    private int lastWrite;
    private Map<String, Map<Long, Find>> lastWritten = Map.of();

    FindFile(Path file, boolean byChunk) {
        this.file = file;
        this.byChunk = byChunk;
    }

    // One find per chunk or one per block.
    static long key(BlockPos pos, boolean byChunk) {
        return byChunk ? ChunkPos.pack(pos) : pos.asLong();
    }

    // The stamp comes first. A write that lands after it shows up at the next look.
    Reading load() throws IOException {
        stamp = SharedFiles.stamp(file);
        held = read();
        return new Reading(held, true);
    }

    // Reads the file only when it changed since this game last read or wrote it.
    Reading look() throws IOException {
        SharedFiles.Stamp now = SharedFiles.stamp(file);
        if (Objects.equals(now, stamp)) {
            return new Reading(null, false);
        }
        stamp = now;
        held = read();
        return new Reading(held, true);
    }

    // Takes in whatever another game wrote and writes the lot. The base is what the game had
    // taken in from the file when it handed its finds over. Heard is the number of its last
    // write it had an answer for. The finds of a later write that reached the file stand in
    // for the base because the game's finds grew from them. Only call it whilst the shared
    // lock is held.
    Reading write(Map<String, Map<Long, Find>> base, Map<String, Map<Long, Find>> mine, int number, int heard)
        throws IOException {
        Map<String, Map<Long, Find>> from = lastWrite > heard ? lastWritten : base;
        Map<String, Map<Long, Find>> theirs = Objects.equals(SharedFiles.stamp(file), stamp) ? held : read();
        if (theirs == null) {
            setAside();
        }
        Map<String, Map<Long, Find>> merged = theirs == null ? mine : merge(from, mine, theirs);
        if (!DataFiles.writeJson(file, encode(merged))) {
            throw new IOException("Could not write " + file.getFileName());
        }
        held = merged;
        stamp = SharedFiles.stamp(file);
        lastWrite = number;
        lastWritten = mine;
        return new Reading(merged, theirs != null && !theirs.equals(from));
    }

    // Both sides started from the base. A find either side removed stays removed and a find
    // either side added or changed stays.
    static Map<String, Map<Long, Find>> merge(Map<String, Map<Long, Find>> base,
                                              Map<String, Map<Long, Find>> mine,
                                              Map<String, Map<Long, Find>> theirs) {
        Set<String> dimensions = new HashSet<>(mine.keySet());
        dimensions.addAll(theirs.keySet());
        Map<String, Map<Long, Find>> merged = new HashMap<>();
        for (String dimension : dimensions) {
            Map<Long, Find> was = base.getOrDefault(dimension, Map.of());
            Map<Long, Find> ours = mine.getOrDefault(dimension, Map.of());
            Map<Long, Find> other = theirs.getOrDefault(dimension, Map.of());
            Set<Long> keys = new HashSet<>(ours.keySet());
            keys.addAll(other.keySet());
            List<Map.Entry<Long, Find>> kept = new ArrayList<>();
            for (long key : keys) {
                Find find = pick(was.get(key), ours.get(key), other.get(key));
                if (find != null) {
                    kept.add(Map.entry(key, find));
                }
            }
            if (!kept.isEmpty()) {
                kept.sort(Comparator.comparingLong(entry -> entry.getValue().found()));
                Map<Long, Find> byKey = new LinkedHashMap<>();
                kept.forEach(entry -> byKey.put(entry.getKey(), entry.getValue()));
                merged.put(dimension, byKey);
            }
        }
        return merged;
    }

    // The find to keep at one spot. A find on one side only stays unless the other side
    // removed it unchanged. Where both hold one theirs wins only if it alone changed and
    // the older sighting keeps its time.
    private static Find pick(Find base, Find mine, Find theirs) {
        if (mine == null || theirs == null) {
            Find only = mine == null ? theirs : mine;
            return only.equals(base) ? null : only;
        }
        Find newer = mine.equals(base) && !theirs.equals(base) ? theirs : mine;
        long found = Math.min(mine.found(), theirs.found());
        return newer.found() == found ? newer
            : new Find(newer.dimension(), newer.pos(), newer.kind(), newer.detail(), found);
    }

    static Map<String, Map<Long, Find>> copy(Map<String, Map<Long, Find>> finds) {
        Map<String, Map<Long, Find>> copy = new HashMap<>();
        finds.forEach((dimension, byKey) -> copy.put(dimension, new LinkedHashMap<>(byKey)));
        return copy;
    }

    // Empty for a missing file and null for one that cannot be read. A second find at a spot
    // another game already wrote is left out.
    private Map<String, Map<Long, Find>> read() {
        if (!Files.exists(file)) {
            return new HashMap<>();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            Map<String, Map<Long, Find>> finds = new HashMap<>();
            for (Map.Entry<String, JsonElement> dimension : root.entrySet()) {
                Map<Long, Find> byKey = new LinkedHashMap<>();
                for (JsonElement element : dimension.getValue().getAsJsonArray()) {
                    Find find = decode(dimension.getKey(), element.getAsJsonObject());
                    byKey.putIfAbsent(key(find.pos(), byChunk), find);
                }
                finds.put(dimension.getKey(), byKey);
            }
            return finds;
        } catch (IOException | RuntimeException e) {
            OfflineClient.LOG.error("Cannot read the finds in {}", file.getFileName(), e);
            return null;
        }
    }

    // A file that cannot be read is kept for the player to mend before a new one takes its place.
    private void setAside() throws IOException {
        Path aside = file.resolveSibling(file.getFileName() + BROKEN);
        Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        OfflineClient.LOG.warn("Moved the unreadable finds file to {}", aside);
    }

    private static Find decode(String dimension, JsonObject o) {
        BlockPos pos = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
        return new Find(dimension, pos, o.get("kind").getAsString(), o.get("detail").getAsString(),
            o.get("found").getAsLong());
    }

    private static JsonObject encode(Map<String, Map<Long, Find>> finds) {
        JsonObject root = new JsonObject();
        finds.forEach((dimension, byKey) -> {
            JsonArray list = new JsonArray();
            for (Find find : byKey.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("x", find.pos().getX());
                o.addProperty("y", find.pos().getY());
                o.addProperty("z", find.pos().getZ());
                o.addProperty("kind", find.kind());
                o.addProperty("detail", find.detail());
                o.addProperty("found", find.found());
                list.add(o);
            }
            if (!list.isEmpty()) {
                root.add(dimension, list);
            }
        });
        return root;
    }

    // One find a line with the time as a spreadsheet reads it.
    static boolean writeCsv(Path file, List<Find> rows) {
        StringBuilder text = new StringBuilder(BYTE_ORDER_MARK).append("dimension,x,y,z,kind,detail,found\n");
        for (Find find : rows) {
            text.append(cell(find.dimension())).append(',').append(find.pos().getX()).append(',')
                .append(find.pos().getY()).append(',').append(find.pos().getZ()).append(',')
                .append(cell(find.kind())).append(',').append(cell(find.detail())).append(',')
                .append(DataFiles.timestamp(find.found()))
                .append('\n');
        }
        return DataFiles.writeSafely(file, temp -> Files.writeString(temp, text));
    }

    // Text holding a comma or a quote or a line break goes inside quotes with its own quotes
    // doubled.
    private static String cell(String text) {
        if (text.chars().noneMatch(c -> c == ',' || c == '"' || c == '\n' || c == '\r')) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
