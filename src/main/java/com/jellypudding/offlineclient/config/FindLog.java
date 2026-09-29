package com.jellypudding.offlineclient.config;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.FindFile.Reading;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

// The finds one module has made on the server you play on. Every dimension keeps its own
// and a new server brings a new set. Finds outlive a toggle for the whole session. With
// Save finds on they sit in a file per module in the server's folder and come back on the
// next visit. Two games on one computer share that file and neither loses what the other
// found or removed. Modules call it on the game thread and the file is read and written
// on a thread of its own.
public final class FindLog {

    // One thing a module found. A module that marks whole chunks gives the block that stands
    // for the chunk. The time is when this game or another one first saw it.
    public record Find(String dimension, BlockPos pos, String kind, String detail, long found) {
    }

    private static final String FOLDER = "finds";

    // Far more finds than any hunt turns up. It only stops a runaway module filling memory.
    private static final int MAX_FINDS = 50_000;

    // A change waits this long for others before the file is written.
    private static final long WRITE_GAP_MS = 3000;
    // How often a log with nothing to write looks for what another game saved.
    private static final long POLL_MS = 5000;

    // Every log whatever module keeps it. The game saves them all as it closes.
    private static final List<FindLog> LOGS = new CopyOnWriteArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(FindLog::saveAllOnExit, "OfflineClient finds save"));
    }

    private final String owner;
    private final boolean byChunk;
    private final BoolSetting save = new BoolSetting("Save finds",
        "Keeps the finds in a file for each server and brings them back next time. "
            + "Two games on this computer share them.", true);
    private final ActionSetting clearHere = new ActionSetting("Clear finds",
        "Forgets every find in the dimension you are in.", () -> Tally.cleared(clearDimension(), "find"))
        .confirm();
    private final ActionSetting clearAll;

    // The finds of the current server by dimension and then by key. Oldest first.
    private Map<String, Map<Long, Find>> finds = new HashMap<>();
    // The kind of each find you took away by hand this session by dimension and then by key.
    private Map<String, Map<Long, String>> dismissed = new HashMap<>();
    // The finds here that passed a module's test and what they were worked out from.
    private List<Find> shown = List.of();
    private int shownRevision = -1;
    private String shownDimension;
    private Object shownKey;
    // What the file held when this game last took it in.
    private Map<String, Map<Long, Find>> base = Map.of();
    // The file of the current server as the file thread knows it.
    private FindFile disk;

    // The server the finds belong to. Null before the first world.
    private String server;
    // Moves on with every new server. A reply that comes back for an older one is dropped.
    private int generation;
    private boolean saving;
    private boolean loaded;
    private boolean dirty;
    private int inFlight;
    // How many writes were asked for on this server and the last one whose answer came back.
    private int writes;
    private int heard;
    private long lastWrite;
    private long lastPoll;
    private int revision;

    // One find for each block.
    public FindLog(Module owner) {
        this(owner, false);
    }

    private FindLog(Module owner, boolean byChunk) {
        this.owner = owner.getName();
        this.byChunk = byChunk;
        clearAll = new ActionSetting("Clear all " + this.owner + " finds",
            "Forgets every find this module made on this server.", () -> Tally.cleared(clearServer(), "find"))
            .confirm();
        OfflineClient.INSTANCE.getEventBus().register(this);
        LOGS.add(this);
    }

    // One find for each chunk. For a module that marks whole chunks.
    public static FindLog byChunk(Module owner) {
        return new FindLog(owner, true);
    }

    // The module keeping the log.
    public String name() {
        return owner;
    }

    public boolean marksChunks() {
        return byChunk;
    }

    public BoolSetting saveSetting() {
        return save;
    }

    // Clears the dimension you are in after a second press.
    public ActionSetting clearDimensionSetting() {
        return clearHere;
    }

    // Clears every dimension of this server after a second press.
    public ActionSetting clearServerSetting() {
        return clearAll;
    }

    // Goes up whenever a find is added changed or removed. A module rebuilds what it shows
    // only when this moves.
    public int revision() {
        return revision;
    }

    // The finds of the dimension you are in as a live view for drawing. Copy it before adding
    // or removing finds whilst walking through it.
    public Collection<Find> here() {
        Map<Long, Find> byKey = current();
        return byKey == null ? List.of() : Collections.unmodifiableCollection(byKey.values());
    }

    public int count() {
        Map<Long, Find> byKey = current();
        return byKey == null ? 0 : byKey.size();
    }

    // The finds here that pass a test. The list is worked out again only when a find or the
    // dimension or the key changes. The key holds whatever the test reads.
    public List<Find> filtered(Object key, Predicate<Find> test) {
        Collection<Find> here = here();
        String dimension = ServerInfo.dimension();
        if (revision != shownRevision || !dimension.equals(shownDimension) || !key.equals(shownKey)) {
            shownRevision = revision;
            shownDimension = dimension;
            shownKey = key;
            List<Find> passing = new ArrayList<>();
            for (Find find : here) {
                if (test.test(find)) {
                    passing.add(find);
                }
            }
            shown = passing;
        }
        return shown;
    }

    // Every find on this server. Each dimension runs oldest first.
    public List<Find> all() {
        follow();
        List<Find> all = new ArrayList<>();
        finds.values().forEach(byKey -> all.addAll(byKey.values()));
        return all;
    }

    // The find at this spot in the dimension you are in. For a log of chunks any spot in
    // the chunk will do. Null when there is none.
    public Find at(BlockPos pos) {
        Map<Long, Find> byKey = current();
        return byKey == null ? null : byKey.get(FindFile.key(pos, byChunk));
    }

    // A find in the dimension you are in. True when nothing was there before. A find already
    // there takes the new kind and words and keeps its time.
    public boolean add(BlockPos pos, String kind, String detail) {
        return add(ServerInfo.dimension(), pos, kind, detail);
    }

    // The same for a find in any dimension of this server. For a module that learns of
    // places in a dimension you are not in. A find you took away by hand this session stays
    // away until its spot shows another kind.
    public boolean add(String dimension, BlockPos pos, String kind, String detail) {
        if (dimension.isEmpty() || OfflineClient.MC.level == null) {
            return false;
        }
        follow();
        long key = FindFile.key(pos, byChunk);
        Map<Long, String> gone = dismissed.get(dimension);
        if (gone != null && gone.containsKey(key)) {
            if (kind.equals(gone.get(key))) {
                return false;
            }
            gone.remove(key);
        }
        Map<Long, Find> byKey = finds.computeIfAbsent(dimension, _ -> new LinkedHashMap<>());
        Find old = byKey.get(key);
        long found = old == null ? System.currentTimeMillis() : old.found();
        Find find = new Find(dimension, pos.immutable(), kind, detail, found);
        if (find.equals(old)) {
            return false;
        }
        byKey.put(key, find);
        if (old == null && total() > MAX_FINDS) {
            dropOldest();
        }
        changed();
        return old == null;
    }

    // Forgets whatever find stands at that find's spot. False when nothing was there.
    public boolean remove(Find find) {
        follow();
        Map<Long, Find> byKey = finds.get(find.dimension());
        if (byKey == null || byKey.remove(FindFile.key(find.pos(), byChunk)) == null) {
            return false;
        }
        changed();
        return true;
    }

    // Forgets a find you took away by hand. The module that made it cannot put the same
    // find back there this session. False when nothing was there.
    public boolean dismiss(Find find) {
        if (!remove(find)) {
            return false;
        }
        dismissed.computeIfAbsent(find.dimension(), _ -> new HashMap<>())
            .put(FindFile.key(find.pos(), byChunk), find.kind());
        return true;
    }

    // Null when this dimension holds no find.
    public Find nearest(Vec3 from) {
        Find best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Find find : here()) {
            double distance = distanceSqr(find, from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = find;
            }
        }
        return best;
    }

    // How far a find lies from a point squared. A chunk mark is measured across the ground.
    public double distanceSqr(Find find, Vec3 from) {
        double dx = find.pos().getX() + 0.5 - from.x;
        double dy = byChunk ? 0 : find.pos().getY() + 0.5 - from.y;
        double dz = find.pos().getZ() + 0.5 - from.z;
        return dx * dx + dy * dy + dz * dz;
    }

    // How many finds the dimension you are in lost.
    public int clearDimension() {
        Map<Long, Find> byKey = current();
        if (byKey == null || byKey.isEmpty()) {
            return 0;
        }
        int count = byKey.size();
        byKey.clear();
        changed();
        return count;
    }

    // How many finds this server lost across every dimension.
    public int clearServer() {
        follow();
        int count = total();
        if (count > 0) {
            finds.clear();
            changed();
        }
        return count;
    }

    // Writes every find on this server to a spreadsheet file beside the saved one. The path
    // comes back on the game thread or null when it could not be written.
    public void export(Consumer<Path> done) {
        follow();
        if (server == null) {
            done.accept(null);
            return;
        }
        List<Find> rows = all();
        Path csv = file().resolveSibling(Setting.idFor(owner) + ".csv");
        SharedFiles.submit(() -> FindFile.writeCsv(csv, rows) ? csv : null, done);
    }

    private void changed() {
        dirty = true;
        revision++;
    }

    private int total() {
        int total = 0;
        for (Map<Long, Find> byKey : finds.values()) {
            total += byKey.size();
        }
        return total;
    }

    // Makes room by forgetting the oldest find of the fullest dimension.
    private void dropOldest() {
        Map<Long, Find> fullest = Collections.max(finds.values(), Comparator.comparingInt(Map::size));
        fullest.remove(fullest.keySet().iterator().next());
    }

    // The finds of the dimension you are in. Null outside a world or before a find there.
    private Map<Long, Find> current() {
        String dimension = ServerInfo.dimension();
        if (dimension.isEmpty()) {
            return null;
        }
        follow();
        return finds.get(dimension);
    }

    // The folder that keeps the saved finds of every module for one server.
    public static Path serverFolder(String server) {
        return DataFiles.path(FOLDER, DataFiles.keyName(server));
    }

    private Path file() {
        return serverFolder(server).resolve(Setting.idFor(owner) + ".json");
    }

    // The finds follow the server you play on. A new one writes what the last was waiting
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
        finds = new HashMap<>();
        dismissed = new HashMap<>();
        base = Map.of();
        disk = new FindFile(file(), byChunk);
        loaded = false;
        dirty = false;
        inFlight = 0;
        writes = 0;
        heard = 0;
        revision++;
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (OfflineClient.MC.level == null) {
            flush(false);
            return;
        }
        follow();
        if (save.isOn() != saving) {
            saving = save.isOn();
            // Switched on it reads the file again and then writes what this session found.
            loaded = false;
        }
        if (!saving || inFlight > 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!loaded) {
            load();
        } else if (dirty && now - lastWrite >= WRITE_GAP_MS) {
            write();
        } else if (now - lastPoll >= POLL_MS) {
            poll();
        }
    }

    // Outside a world what is waiting is written once the last answer is in and the usual
    // pause has passed. A new server writes it straight away.
    private void flush(boolean atOnce) {
        if (!saving || !dirty || server == null) {
            return;
        }
        if (atOnce || inFlight == 0 && System.currentTimeMillis() - lastWrite >= WRITE_GAP_MS) {
            write();
        }
    }

    private void load() {
        FindFile file = disk;
        int asked = generation;
        inFlight++;
        lastPoll = System.currentTimeMillis();
        SharedFiles.submit(file::load, reading -> {
            if (asked == generation) {
                loaded = true;
                took(reading);
            }
        });
    }

    private void poll() {
        FindFile file = disk;
        int asked = generation;
        inFlight++;
        lastPoll = System.currentTimeMillis();
        SharedFiles.submit(file::look, reading -> {
            if (asked == generation) {
                took(reading);
            }
        });
    }

    // Takes in what the other game saved. Whatever this game changed since stays.
    private void took(Reading reading) {
        inFlight--;
        if (reading == null || !reading.news()) {
            return;
        }
        if (reading.finds() == null) {
            // A broken file is set aside and written over with what this game holds.
            dirty = true;
            return;
        }
        finds = FindFile.merge(base, finds, reading.finds());
        base = reading.finds();
        revision++;
    }

    private void write() {
        Map<String, Map<Long, Find>> snapshot = FindFile.copy(finds);
        int number = ++writes;
        SharedFiles.FileWork<Reading> work = writeWork(snapshot, number);
        int asked = generation;
        dirty = false;
        lastWrite = System.currentTimeMillis();
        inFlight++;
        SharedFiles.submit(work, reading -> {
            if (asked == generation) {
                written(snapshot, number, reading);
            }
        });
    }

    // A write that takes what it needs from the game thread now and runs on the file thread.
    private SharedFiles.FileWork<Reading> writeWork(Map<String, Map<Long, Find>> snapshot, int number) {
        FindFile file = disk;
        Map<String, Map<Long, Find>> from = base;
        int answered = heard;
        return () -> SharedFiles.locked(() -> file.write(from, snapshot, number, answered));
    }

    private void written(Map<String, Map<Long, Find>> snapshot, int number, Reading reading) {
        inFlight--;
        heard = number;
        if (reading == null) {
            // Tried again after the usual pause.
            dirty = true;
            return;
        }
        if (reading.news()) {
            finds = FindFile.merge(snapshot, finds, reading.finds());
            revision++;
        }
        base = reading.finds();
    }

    // Writes every log that still holds unsaved finds as the game closes. The writes queue
    // behind whatever the file thread still has to do and the game waits for all of it.
    private static void saveAllOnExit() {
        List<Runnable> pending = new ArrayList<>();
        for (FindLog log : LOGS) {
            if (!log.saving || !log.dirty || log.server == null) {
                continue;
            }
            SharedFiles.FileWork<Reading> work = log.writeWork(FindFile.copy(log.finds), ++log.writes);
            String owner = log.owner;
            pending.add(() -> {
                try {
                    work.run();
                } catch (IOException | RuntimeException e) {
                    OfflineClient.LOG.error("Could not save the finds of {}", owner, e);
                }
            });
        }
        SharedFiles.await(() -> {
            pending.forEach(Runnable::run);
            return null;
        });
    }
}
