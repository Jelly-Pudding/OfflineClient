package com.jellypudding.offlineclient.config;

import com.google.gson.JsonElement;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.WorldWatch;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// What the client learnt on the server you play on kept by key in a file beside the finds.
// The file of a server is read the first time anything asks after you arrive. Two games on
// one computer add to the same file and neither loses what the other learnt or forgot.
// Game thread only.
public abstract class ServerLedger<K, V> {

    // A change waits this long for others before the file is written. A failed write waits
    // as long before it is tried again.
    private static final long WRITE_GAP_MS = 5000;
    // Changes that never pause are still written this often.
    private static final long LONGEST_WAIT_MS = 60_000;

    private final String fileName;
    private final int capacity;
    // What the entries are in words for the log.
    private final String what;
    // Only a new world can bring a new server.
    private final WorldWatch world = new WorldWatch();
    // False until anything asks. Nothing can change before then and nothing waits to be written.
    private boolean awake;

    // The least recently used come first.
    private Map<K, V> entries;
    // What this game changed since it last wrote. A key with no entry was forgotten.
    private Set<K> touched = new HashSet<>();
    // The server the entries belong to. Null before the first world.
    private String server;
    // The registries of the connection the entries were read under. Every read and write of the
    // file is handed ops made from them before it leaves for the file thread.
    private RegistryAccess registries;
    // Moves on with every new server or connection and when everything is forgotten. A reply
    // that comes back for an older one is dropped.
    private int generation;
    private boolean busy;
    // False until the file of the current server has been read.
    private boolean loaded;
    private long changedAt;
    // When the oldest change still waiting was made.
    private long waitingSince;
    private long lastWrite;

    // The capacity only bounds the memory and the file.
    protected ServerLedger(String fileName, int capacity, String what) {
        this.fileName = fileName;
        this.capacity = capacity;
        this.what = what;
        entries = bounded();
    }

    // Reads the entries a file holds into the map. Runs on the file thread.
    protected abstract void parse(JsonElement root, Map<K, V> into, RegistryOps<JsonElement> ops);

    // Runs on the file thread. The game thread leaves these entries alone.
    protected abstract JsonElement encode(Map<K, V> entries, RegistryOps<JsonElement> ops);

    // What the file thread is handed for an entry. One that changes in place hands a copy.
    protected V frozen(V value) {
        return value;
    }

    // An answer given before the file is read could take an entry the file holds for a missing one.
    public final boolean ready() {
        follow();
        return server != null && loaded;
    }

    // Null for a key never learnt and whilst the file is still being read.
    public final V get(K key) {
        follow();
        return entries.get(key);
    }

    // Every entry of the server you play on. Reading one with get whilst going through
    // these moves it and breaks the walk.
    public final Collection<V> values() {
        follow();
        return Collections.unmodifiableCollection(entries.values());
    }

    // A change before the file is read would hide the entry the file holds.
    public final void put(K key, V value) {
        follow();
        if (!loaded || value.equals(entries.get(key))) {
            return;
        }
        entries.put(key, value);
        touch(key);
    }

    // Writes an entry the caller changed in place.
    public final void changed(K key) {
        follow();
        if (loaded && entries.containsKey(key)) {
            touch(key);
        }
    }

    // Takes the entry out of the file as well. False when there was none.
    public final boolean forget(K key) {
        follow();
        if (!loaded || entries.remove(key) == null) {
            return false;
        }
        touch(key);
        return true;
    }

    // Forgets every entry of the server you play on and deletes its file. Whatever this game
    // was still reading or writing for it comes to nothing.
    public final void forgetAll() {
        follow();
        if (server == null) {
            return;
        }
        generation++;
        busy = false;
        loaded = true;
        entries = bounded();
        touched = new HashSet<>();
        Path file = file();
        SharedFiles.submit(() -> SharedFiles.locked(() -> Files.deleteIfExists(file)), _ -> {
        });
    }

    // The server the entries belong to. Null before the first world.
    protected final String server() {
        follow();
        return server;
    }

    private void touch(K key) {
        long now = System.currentTimeMillis();
        if (touched.isEmpty()) {
            waitingSince = now;
        }
        touched.add(key);
        changedAt = now;
    }

    private Map<K, V> bounded() {
        return new BoundedMap<>(capacity, true);
    }

    private Path file() {
        return FindLog.serverFolder(server).resolve(fileName);
    }

    private RegistryOps<JsonElement> ops() {
        return registries.createSerializationContext(JsonOps.INSTANCE);
    }

    // The entries follow the server you play on. A new one writes what the last was waiting
    // for and starts again from its own file. The answer to that write is dropped. Joining the
    // same server again starts afresh too since what was read under the registries of one
    // connection cannot be written under those of another.
    private void follow() {
        if (!awake) {
            awake = true;
            OfflineClient.INSTANCE.getEventBus().register(this);
            Runtime.getRuntime().addShutdownHook(new Thread(this::saveOnExit, "OfflineClient " + what + " save"));
        }
        if (OfflineClient.MC.level == null || !world.changed()) {
            return;
        }
        String now = ServerInfo.key();
        RegistryAccess joined = OfflineClient.MC.level.registryAccess();
        if (now.equals(server) && joined == registries) {
            return;
        }
        if (server != null && !touched.isEmpty()) {
            write();
        }
        server = now;
        registries = joined;
        generation++;
        entries = bounded();
        touched = new HashSet<>();
        loaded = false;
        load();
    }

    // Changes made on a server you since left still go to that server's file.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (busy || touched.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        // Outside a world nothing more is coming and only the pause after the last write counts.
        boolean paused = now - (OfflineClient.MC.level == null ? lastWrite : changedAt) >= WRITE_GAP_MS;
        if (paused || now - waitingSince >= LONGEST_WAIT_MS) {
            write();
        }
    }

    private void load() {
        Path file = file();
        RegistryOps<JsonElement> ops = ops();
        int asked = generation;
        busy = true;
        SharedFiles.submit(() -> decode(file, ops), read -> {
            if (asked == generation) {
                busy = false;
                loaded = true;
                absorb(read);
            }
        });
    }

    private void write() {
        Path file = file();
        RegistryOps<JsonElement> ops = ops();
        Map<K, V> mine = unsaved();
        Set<K> sent = touched;
        touched = new HashSet<>();
        int asked = generation;
        busy = true;
        lastWrite = System.currentTimeMillis();
        SharedFiles.submit(() -> SharedFiles.locked(() -> merge(file, mine, ops)), merged -> {
            if (asked != generation) {
                return;
            }
            busy = false;
            if (merged == null) {
                // Tried again after the usual pause.
                sent.forEach(this::touch);
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
            SharedFiles.locked(() -> merge(file(), unsaved(), ops()));
        } catch (IOException | RuntimeException e) {
            OfflineClient.LOG.error("Could not save the {}", what, e);
        }
    }

    // The entries this game changed since it last wrote. A forgotten one maps to null.
    private Map<K, V> unsaved() {
        Map<K, V> mine = new HashMap<>();
        for (K key : touched) {
            V value = entries.get(key);
            mine.put(key, value == null ? null : frozen(value));
        }
        return mine;
    }

    // Takes in what the file holds. Whatever this game changed since it last wrote stays and
    // what another game forgot goes.
    private void absorb(Map<K, V> theirs) {
        if (theirs == null) {
            return;
        }
        Map<K, V> kept = bounded();
        kept.putAll(theirs);
        for (K key : touched) {
            V ours = entries.get(key);
            if (ours == null) {
                kept.remove(key);
            } else {
                kept.put(key, ours);
            }
        }
        entries = kept;
    }

    // The file with this game's changes laid over it. Only call it whilst the shared lock is held.
    private Map<K, V> merge(Path file, Map<K, V> mine, RegistryOps<JsonElement> ops) throws IOException {
        // A broken file is written over with what this game holds.
        Map<K, V> merged = Objects.requireNonNullElseGet(decode(file, ops), this::bounded);
        mine.forEach((key, value) -> {
            if (value == null) {
                merged.remove(key);
            } else {
                merged.put(key, value);
            }
        });
        if (!DataFiles.writeJson(file, encode(merged, ops))) {
            throw new IOException("Could not write " + file.getFileName());
        }
        return merged;
    }

    // Empty for a missing file and null for one that cannot be read.
    private Map<K, V> decode(Path file, RegistryOps<JsonElement> ops) {
        if (!Files.exists(file)) {
            return bounded();
        }
        return DataFiles.readJson(file, root -> {
            Map<K, V> read = bounded();
            parse(root, read, ops);
            return read;
        }).orElse(null);
    }
}
