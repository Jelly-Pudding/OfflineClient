package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ServerInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// What each spawner on the server you play on showed the last time it was read and how long
// you stood within its reach since. A reading that moved further than your own time there
// explains was moved by someone else. Spots you came near or dug in are remembered for good.
// Every dimension keeps its own. They sit in a file beside the finds and come back after a
// restart. Two games on one computer add to the same file and neither loses what the other
// read. Game thread only.
public final class SpawnerLedger {

    // A spawner's count or a trial spawner's state when it was last read and the ticks you
    // stood within its reach since. Near says you came within reach of the spot at any time.
    public record Entry(int reading, int ticksNear, boolean near) {

        // False for a spot you came near or dug in whose spawner was never read.
        public boolean hasReading() {
            return reading != NO_READING;
        }
    }

    private static final String FILE = "spawnerreadings.json";
    private static final int NO_READING = Integer.MIN_VALUE;
    // A line with three parts says only whether you went near. Its time near is taken as long
    // enough to explain any count.
    private static final int LONG_SPELL = 72_000;
    // Far more spawners than a hunt passes. It only bounds the memory and the file.
    private static final int MAX_PER_DIMENSION = 65_536;
    // A change waits this long for others before the file is written. A failed write waits
    // as long before it is tried again.
    private static final long WRITE_GAP_MS = 5000;
    private static final String NEAR = "1";
    private static final String NEVER_NEAR = "0";

    // By dimension and then by block position. The least recently used come first.
    private Map<String, Map<Long, Entry>> entries = new HashMap<>();
    // What this game read or visited since it last wrote. It wins over the file.
    private Map<String, Set<Long>> touched = new HashMap<>();
    // The server the readings belong to. Null before the first world.
    private String server;
    // Moves on with every new server. A reply that comes back for an older one is dropped.
    private int generation;
    private boolean busy;
    // False until the file of the current server has been read.
    private boolean loaded;
    private long changedAt;
    private long lastWrite;

    public SpawnerLedger() {
        OfflineClient.INSTANCE.getEventBus().register(this);
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveOnExit, "OfflineClient spawner readings save"));
    }

    // A reading judged before the file is read could take your own old visit for a stranger's.
    public boolean ready() {
        follow();
        return server != null && loaded;
    }

    // Null for a spot never read or visited in the dimension you are in.
    public Entry get(long pos) {
        Map<Long, Entry> here = here();
        return here == null ? null : here.get(pos);
    }

    // A new reading starts the time near afresh. Near stays once it was set.
    public void read(long pos, int reading, int ticksNear, boolean near) {
        Entry old = get(pos);
        put(pos, new Entry(reading, ticksNear, near || old != null && old.near()));
    }

    // One more tick near a spawner or one more dig in a room since its last reading.
    public void cameNear(long pos) {
        Entry old = get(pos);
        put(pos, old == null ? new Entry(NO_READING, 1, true) : new Entry(old.reading(), old.ticksNear() + 1, true));
    }

    // Someone took a spawner whilst you stood away. Your visits before cannot explain it.
    public void takenByOthers(long pos) {
        Entry old = get(pos);
        if (old != null && old.ticksNear() > 0) {
            put(pos, new Entry(old.reading(), 0, old.near()));
        }
    }

    // A change before the file is read would hide the reading the file holds.
    private void put(long pos, Entry entry) {
        String dimension = ServerInfo.dimension();
        if (dimension.isEmpty()) {
            return;
        }
        follow();
        if (!loaded) {
            return;
        }
        entries.computeIfAbsent(dimension, _ -> bounded()).put(pos, entry);
        touched.computeIfAbsent(dimension, _ -> new HashSet<>()).add(pos);
        changedAt = System.currentTimeMillis();
    }

    private Map<Long, Entry> here() {
        String dimension = ServerInfo.dimension();
        if (dimension.isEmpty()) {
            return null;
        }
        follow();
        return entries.get(dimension);
    }

    private static Map<Long, Entry> bounded() {
        return new BoundedMap<>(MAX_PER_DIMENSION, true);
    }

    private Path file() {
        return FindLog.serverFolder(server).resolve(FILE);
    }

    // The readings follow the server you play on. A new one writes what the last was waiting
    // for and starts again from its own file.
    private void follow() {
        if (OfflineClient.MC.level == null) {
            return;
        }
        String now = ServerInfo.key();
        if (now.equals(server)) {
            return;
        }
        flush(true);
        server = now;
        generation++;
        entries = new HashMap<>();
        touched = new HashMap<>();
        loaded = false;
        load();
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (OfflineClient.MC.level == null) {
            flush(false);
            return;
        }
        follow();
        if (!busy && !touched.isEmpty() && System.currentTimeMillis() - changedAt >= WRITE_GAP_MS) {
            write();
        }
    }

    // Outside a world what is waiting is written once the pause after the last write has
    // passed. A new server writes it straight away.
    private void flush(boolean atOnce) {
        if (server != null && !busy && !touched.isEmpty()
            && (atOnce || System.currentTimeMillis() - lastWrite >= WRITE_GAP_MS)) {
            write();
        }
    }

    private void load() {
        Path file = file();
        int asked = generation;
        busy = true;
        SharedFiles.submit(() -> decode(file), read -> {
            if (asked == generation) {
                busy = false;
                loaded = true;
                absorb(read);
            }
        });
    }

    private void write() {
        Path file = file();
        Map<String, Map<Long, Entry>> mine = unsaved();
        Map<String, Set<Long>> sent = touched;
        touched = new HashMap<>();
        int asked = generation;
        busy = true;
        lastWrite = System.currentTimeMillis();
        SharedFiles.submit(() -> SharedFiles.locked(() -> merge(file, mine)), merged -> {
            if (asked != generation) {
                return;
            }
            busy = false;
            if (merged == null) {
                // Tried again after the usual pause.
                sent.forEach((dimension, keys) -> touched.computeIfAbsent(dimension, _ -> new HashSet<>())
                    .addAll(keys));
                changedAt = System.currentTimeMillis();
                return;
            }
            absorb(merged);
        });
    }

    private void saveOnExit() {
        if (server == null || touched.isEmpty()) {
            return;
        }
        try {
            SharedFiles.locked(() -> merge(file(), unsaved()));
        } catch (IOException | RuntimeException e) {
            OfflineClient.LOG.error("Could not save the spawner readings", e);
        }
    }

    // The readings this game changed since it last wrote.
    private Map<String, Map<Long, Entry>> unsaved() {
        Map<String, Map<Long, Entry>> mine = new HashMap<>();
        touched.forEach((dimension, keys) -> {
            Map<Long, Entry> byPos = entries.getOrDefault(dimension, Map.of());
            Map<Long, Entry> copy = new HashMap<>();
            for (long pos : keys) {
                Entry entry = byPos.get(pos);
                if (entry != null) {
                    copy.put(pos, entry);
                }
            }
            mine.put(dimension, copy);
        });
        return mine;
    }

    // Takes in what the file holds. Whatever this game changed since it last wrote stays.
    private void absorb(Map<String, Map<Long, Entry>> theirs) {
        if (theirs == null) {
            return;
        }
        theirs.forEach((dimension, byPos) -> {
            Set<Long> ours = touched.getOrDefault(dimension, Set.of());
            Map<Long, Entry> here = entries.computeIfAbsent(dimension, _ -> bounded());
            byPos.forEach((pos, entry) -> {
                if (!ours.contains(pos)) {
                    here.put(pos, entry);
                }
            });
        });
    }

    // The file with this game's changes laid over it. Only call it whilst the shared lock is held.
    private static Map<String, Map<Long, Entry>> merge(Path file, Map<String, Map<Long, Entry>> mine)
        throws IOException {
        // A broken file is written over with what this game holds.
        Map<String, Map<Long, Entry>> merged = Objects.requireNonNullElseGet(decode(file), HashMap::new);
        mine.forEach((dimension, byPos) -> merged.computeIfAbsent(dimension, _ -> bounded()).putAll(byPos));
        if (!DataFiles.writeJson(file, encode(merged))) {
            throw new IOException("Could not write " + file.getFileName());
        }
        return merged;
    }

    // Empty for a missing file and null for one that cannot be read.
    private static Map<String, Map<Long, Entry>> decode(Path file) {
        if (!Files.exists(file)) {
            return new HashMap<>();
        }
        return DataFiles.readJson(file, SpawnerLedger::parse).orElse(null);
    }

    private static Map<String, Map<Long, Entry>> parse(JsonElement root) {
        Map<String, Map<Long, Entry>> read = new HashMap<>();
        for (Map.Entry<String, JsonElement> dimension : root.getAsJsonObject().entrySet()) {
            Map<Long, Entry> byPos = bounded();
            for (JsonElement line : dimension.getValue().getAsJsonArray()) {
                String[] parts = line.getAsString().split(" ");
                byPos.put(Long.parseLong(parts[0]), entry(parts));
            }
            read.put(dimension.getKey(), byPos);
        }
        return read;
    }

    private static Entry entry(String[] parts) {
        int reading = Integer.parseInt(parts[1]);
        if (parts.length < 4) {
            boolean near = NEAR.equals(parts[2]);
            return new Entry(reading, near ? LONG_SPELL : 0, near);
        }
        return new Entry(reading, Integer.parseInt(parts[2]), NEAR.equals(parts[3]));
    }

    // One line for each spot such as its position and its reading and the ticks you stood near
    // since and whether you ever went near.
    private static JsonObject encode(Map<String, Map<Long, Entry>> readings) {
        JsonObject root = new JsonObject();
        readings.forEach((dimension, byPos) -> {
            JsonArray lines = new JsonArray();
            byPos.forEach((pos, entry) -> lines.add(pos + " " + entry.reading() + " " + entry.ticksNear() + " "
                + (entry.near() ? NEAR : NEVER_NEAR)));
            if (!lines.isEmpty()) {
                root.add(dimension, lines);
            }
        });
        return root;
    }
}
