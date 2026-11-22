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
import com.jellypudding.offlineclient.util.TextCut;
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

// Two games on one computer share the file and neither loses what the other found or removed.
// Modules call it on the game thread and the file is read and written on a thread of its own.
public final class FindLog {

    // The content is kept and saved but never shown. The time is when this game or another one
    // first saw it.
    public record Find(String dimension, BlockPos pos, String kind, String detail, List<String> content,
                       long found) {

        public Find {
            kind = TextCut.whole(kind);
            detail = TextCut.whole(detail);
            content = content.stream().map(TextCut::whole).toList();
        }

        public Find(String dimension, BlockPos pos, String kind, String detail, long found) {
            this(dimension, pos, kind, detail, List.of(), found);
        }
    }

    private static final String FOLDER = "finds";

    // Far more finds than any hunt turns up. It only stops a runaway module filling memory.
    private static final int MAX_FINDS = 50_000;

    private static final long WRITE_GAP_MS = 3000;
    private static final long POLL_MS = 5000;

    private static final List<FindLog> LOGS = new CopyOnWriteArrayList<>();

    private static int newFinds;

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

    private Map<String, Map<Long, Find>> finds = new HashMap<>();
    private Map<String, Map<Long, String>> dismissed = new HashMap<>();
    private List<Find> shown = List.of();
    private int shownRevision = -1;
    private String shownDimension;
    private Object shownKey;
    private Map<String, Map<Long, Find>> base = Map.of();
    private FindFile disk;

    private String server;
    // Moves on with every new server. A reply that comes back for an older one is dropped.
    private int generation;
    private boolean saving;
    private boolean loaded;
    private boolean dirty;
    private int inFlight;
    private int writes;
    private int heard;
    private long lastWrite;
    private long lastPoll;
    private int revision;

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

    public static FindLog byChunk(Module owner) {
        return new FindLog(owner, true);
    }

    public String name() {
        return owner;
    }

    public boolean marksChunks() {
        return byChunk;
    }

    public BoolSetting saveSetting() {
        return save;
    }

    public ActionSetting clearDimensionSetting() {
        return clearHere;
    }

    public ActionSetting clearServerSetting() {
        return clearAll;
    }

    public int revision() {
        return revision;
    }

    // False whilst the saved finds of the server you joined are still being read. A module that
    // compares what it sees with its finds waits for them.
    public boolean ready() {
        follow();
        return server != null && (!save.isOn() || loaded);
    }

    // Finds read from a file never move it.
    public static int newFinds() {
        return newFinds;
    }

    // A live view. Copy it before adding or removing finds whilst walking through it.
    public Collection<Find> here() {
        Map<Long, Find> byKey = current();
        return byKey == null ? List.of() : Collections.unmodifiableCollection(byKey.values());
    }

    public int count() {
        Map<Long, Find> byKey = current();
        return byKey == null ? 0 : byKey.size();
    }

    // The key holds whatever the test reads. The list is worked out again only when a find or the
    // dimension or the key changes.
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

    public List<Find> all() {
        follow();
        List<Find> all = new ArrayList<>();
        finds.values().forEach(byKey -> all.addAll(byKey.values()));
        return all;
    }

    public Find at(BlockPos pos) {
        Map<Long, Find> byKey = current();
        return byKey == null ? null : byKey.get(FindFile.key(pos, byChunk));
    }

    // True when nothing was there before. A find already there takes the new kind and words and
    // keeps its time.
    public boolean add(BlockPos pos, String kind, String detail) {
        return add(ServerInfo.dimension(), pos, kind, detail, List.of());
    }

    public boolean add(BlockPos pos, String kind, String detail, List<String> content) {
        return add(ServerInfo.dimension(), pos, kind, detail, content);
    }

    public boolean add(String dimension, BlockPos pos, String kind, String detail) {
        return add(dimension, pos, kind, detail, List.of());
    }

    // A find you took away by hand this session stays away until its spot shows another kind.
    public boolean add(String dimension, BlockPos pos, String kind, String detail, List<String> content) {
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
        Find find = new Find(dimension, pos.immutable(), kind, detail, content, found);
        if (find.equals(old)) {
            return false;
        }
        byKey.put(key, find);
        if (old == null) {
            newFinds++;
            if (total() > MAX_FINDS) {
                dropOldest();
            }
        }
        changed();
        return old == null;
    }

    public boolean remove(Find find) {
        follow();
        Map<Long, Find> byKey = finds.get(find.dimension());
        if (byKey == null || byKey.remove(FindFile.key(find.pos(), byChunk)) == null) {
            return false;
        }
        changed();
        return true;
    }

    // The module that made it cannot put the same find back there this session.
    public boolean dismiss(Find find) {
        if (!remove(find)) {
            return false;
        }
        dismissed.computeIfAbsent(find.dimension(), _ -> new HashMap<>())
            .put(FindFile.key(find.pos(), byChunk), find.kind());
        return true;
    }

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

    // A chunk mark is measured across the ground.
    public double distanceSqr(Find find, Vec3 from) {
        double dx = find.pos().getX() + 0.5 - from.x;
        double dy = byChunk ? 0 : find.pos().getY() + 0.5 - from.y;
        double dz = find.pos().getZ() + 0.5 - from.z;
        return dx * dx + dy * dy + dz * dz;
    }

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

    public int clearServer() {
        follow();
        int count = total();
        if (count > 0) {
            finds.clear();
            changed();
        }
        return count;
    }

    // The path comes back on the game thread or null when it could not be written.
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

    private void dropOldest() {
        Map<Long, Find> fullest = Collections.max(finds.values(), Comparator.comparingInt(Map::size));
        fullest.remove(fullest.keySet().iterator().next());
    }

    private Map<Long, Find> current() {
        String dimension = ServerInfo.dimension();
        if (dimension.isEmpty()) {
            return null;
        }
        follow();
        return finds.get(dimension);
    }

    public static Path serverFolder(String server) {
        return DataFiles.path(FOLDER, DataFiles.keyName(server));
    }

    private Path file() {
        return serverFolder(server).resolve(Setting.idFor(owner) + ".json");
    }

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
            dirty = true;
            return;
        }
        if (reading.news()) {
            finds = FindFile.merge(snapshot, finds, reading.finds());
            revision++;
        }
        base = reading.finds();
    }

    // The writes queue behind whatever the file thread still has to do and the game waits for all
    // of it.
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
