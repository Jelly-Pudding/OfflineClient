package com.jellypudding.offlineclient.config;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ServerInfo;
import it.unimi.dsi.fastutil.bytes.ByteIterator;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMaps;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The first letter a chunk gets stays. What another game on this computer adds to the file turns up
// within seconds. Modules call it on the game thread and the files are read and written on the
// shared file thread.
public final class ChunkMarks {

    // A whole reading holds every mark in the file. Otherwise it holds the lines another game added
    // past the end this game had reached.
    record Reading(String name, long end, Long2ByteOpenHashMap marks, boolean whole) {
    }

    private static final String FOLDER = "chunks";
    private static final String TEXT = ".txt";
    // The first line of a file holds a name that changes whenever the file is written anew.
    private static final String HEADER = "# chunk marks ";
    private static final int HEADER_BYTES = 64;
    private static final Pattern MARK = Pattern.compile("(-?\\d+) (-?\\d+) (\\p{Lower})");
    private static final int LETTERS = 128;

    private static final long WRITE_GAP_MS = 3000;
    private static final long POLL_MS = 5000;
    // A file this many lines longer than the chunks it holds is written again without repeats.
    private static final int SPARE_LINES = 20_000;

    private static final List<ChunkMarks> ALL = new CopyOnWriteArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ChunkMarks::saveAllOnExit, "OfflineClient chunk marks save"));
    }

    private static final class Marks {
        private Long2ByteOpenHashMap byChunk = new Long2ByteOpenHashMap();
        private final int[] counts = new int[LETTERS];
        // Every mark this game made here in order. The first ones up to written are in the file.
        private LongArrayList made = new LongArrayList();
        private int written;
        private String name;
        private long end;
        private boolean loaded;
        private boolean busy;
        // Moves on with every clear and with loading switched off. Answers from before are dropped.
        private int epoch;
        private long lastWrite;
        private long lastPoll;

        private boolean putIfAbsent(long chunk, byte letter) {
            if (byChunk.containsKey(chunk)) {
                return false;
            }
            byChunk.put(chunk, letter);
            counts[letter]++;
            return true;
        }

        private void replace(Long2ByteOpenHashMap marks) {
            byChunk = marks;
            Arrays.fill(counts, 0);
            for (ByteIterator letters = marks.values().iterator(); letters.hasNext(); ) {
                counts[letters.nextByte()]++;
            }
        }
    }

    private final Module owner;
    private final BoolSetting save = new BoolSetting("Save chunks",
        "Writes every marked chunk to a file for this server and dimension.", true);
    private final BoolSetting load = new BoolSetting("Load chunks",
        "Shows the chunks saved on earlier visits and the ones another game on this computer saves "
            + "whilst you play.", true);

    private final Map<String, Marks> dimensions = new HashMap<>();
    private String server;
    // Moves on with every new server. An answer that comes back for an older one is dropped.
    private int generation;
    private boolean saving;
    private boolean loading;
    private int revision;

    public ChunkMarks(Module owner) {
        this.owner = owner;
        ALL.add(this);
        OfflineClient.INSTANCE.getEventBus().register(this);
    }

    public Setting<?>[] settings() {
        return new Setting<?>[] {save, load};
    }

    public int revision() {
        return revision;
    }

    public byte get(long chunk) {
        Marks marks = current(false);
        return marks == null ? 0 : marks.byChunk.get(chunk);
    }

    // False when it already had one. That one stays.
    public boolean mark(long chunk, byte letter) {
        Marks marks = current(true);
        if (marks == null || !marks.putIfAbsent(chunk, letter)) {
            return false;
        }
        marks.made.add(chunk);
        revision++;
        return true;
    }

    public Long2ByteMap here() {
        Marks marks = current(false);
        return marks == null ? Long2ByteMaps.EMPTY_MAP : Long2ByteMaps.unmodifiable(marks.byChunk);
    }

    public int count(byte letter) {
        Marks marks = current(false);
        return marks == null ? 0 : marks.counts[letter];
    }

    // Other games that load the file forget them too.
    public int clearDimension() {
        Marks marks = current(true);
        if (marks == null) {
            return 0;
        }
        int count = marks.byChunk.size();
        marks.epoch++;
        marks.replace(new Long2ByteOpenHashMap());
        marks.made = new LongArrayList();
        marks.written = 0;
        marks.name = null;
        marks.end = 0;
        marks.loaded = false;
        marks.busy = true;
        Path file = file(ServerInfo.dimension());
        SharedFiles.submit(() -> SharedFiles.locked(() -> emptied(file)), _ -> marks.busy = false);
        revision++;
        return count;
    }

    public void forget() {
        flushAll(true);
        dimensions.clear();
        revision++;
    }

    private Marks current(boolean create) {
        String dimension = ServerInfo.dimension();
        if (dimension.isEmpty()) {
            return null;
        }
        follow();
        return create ? dimensions.computeIfAbsent(dimension, _ -> new Marks()) : dimensions.get(dimension);
    }

    private Path file(String dimension) {
        return DataFiles.path(FOLDER, DataFiles.keyName(server), DataFiles.safeName(dimension) + TEXT);
    }

    private void follow() {
        if (OfflineClient.MC.level == null) {
            return;
        }
        String now = ServerInfo.key();
        if (now.equals(server)) {
            return;
        }
        flushAll(true);
        server = now;
        generation++;
        dimensions.clear();
        revision++;
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (OfflineClient.MC.level == null) {
            flushAll(false);
            return;
        }
        follow();
        syncSwitches();
        String here = ServerInfo.dimension();
        boolean reads = loading && owner.isEnabled();
        if (reads) {
            dimensions.computeIfAbsent(here, _ -> new Marks());
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Marks> entry : dimensions.entrySet()) {
            Marks marks = entry.getValue();
            boolean polls = reads && entry.getKey().equals(here) && now - marks.lastPoll >= POLL_MS;
            if (marks.busy) {
                continue;
            }
            if (polls && !marks.loaded) {
                readWhole(entry.getKey(), marks);
            } else if (saving && marks.written < marks.made.size() && now - marks.lastWrite >= WRITE_GAP_MS) {
                write(entry.getKey(), marks);
            } else if (polls) {
                readNews(entry.getKey(), marks);
            }
        }
    }

    private void syncSwitches() {
        saving = save.isOn();
        if (load.isOn() == loading) {
            return;
        }
        loading = load.isOn();
        for (Marks marks : dimensions.values()) {
            if (loading) {
                marks.loaded = false;
            } else {
                keepOwn(marks);
            }
        }
        revision++;
    }

    private static void keepOwn(Marks marks) {
        Long2ByteOpenHashMap own = new Long2ByteOpenHashMap();
        for (int i = 0; i < marks.made.size(); i++) {
            long chunk = marks.made.getLong(i);
            own.put(chunk, marks.byChunk.get(chunk));
        }
        marks.replace(own);
        marks.name = null;
        marks.end = 0;
        marks.loaded = false;
        marks.epoch++;
    }

    // Leaving a server writes even what a write under way may already hold because the marks are
    // dropped after.
    private void flushAll(boolean leaving) {
        if (!saving || server == null) {
            return;
        }
        for (Map.Entry<String, Marks> entry : dimensions.entrySet()) {
            Marks marks = entry.getValue();
            if ((leaving || !marks.busy) && marks.written < marks.made.size()) {
                write(entry.getKey(), marks);
            }
        }
    }

    private void readWhole(String dimension, Marks marks) {
        Path file = file(dimension);
        int asked = generation;
        int epoch = marks.epoch;
        marks.busy = true;
        marks.lastPoll = System.currentTimeMillis();
        SharedFiles.submit(() -> SharedFiles.locked(() -> wholeFile(file, true)), reading -> {
            marks.busy = false;
            if (reading != null && asked == generation && epoch == marks.epoch) {
                marks.loaded = true;
                took(marks, reading);
            }
        });
    }

    private void readNews(String dimension, Marks marks) {
        Path file = file(dimension);
        String name = marks.name;
        long end = marks.end;
        int asked = generation;
        int epoch = marks.epoch;
        marks.busy = true;
        marks.lastPoll = System.currentTimeMillis();
        SharedFiles.submit(() -> readAfter(file, name, end), reading -> {
            marks.busy = false;
            if (reading != null && asked == generation && epoch == marks.epoch) {
                took(marks, reading);
            }
        });
    }

    private void write(String dimension, Marks marks) {
        Path file = file(dimension);
        int upTo = marks.made.size();
        String lines = lines(marks, marks.written, upTo);
        String name = marks.name;
        long end = marks.end;
        boolean follows = marks.loaded;
        int asked = generation;
        int epoch = marks.epoch;
        marks.busy = true;
        marks.lastWrite = System.currentTimeMillis();
        SharedFiles.submit(() -> SharedFiles.locked(() -> append(file, lines, name, end, follows)), reading -> {
            marks.busy = false;
            if (reading == null || asked != generation || epoch != marks.epoch) {
                // A failed write is tried again after the usual pause.
                return;
            }
            marks.written = Math.max(marks.written, upTo);
            if (follows) {
                took(marks, reading);
            }
        });
    }

    // The first mark a chunk ever got wins. Lines another game added only fill gaps.
    private void took(Marks marks, Reading reading) {
        marks.name = reading.name();
        marks.end = reading.end();
        if (reading.whole()) {
            takeWhole(marks, reading.marks());
            revision++;
            return;
        }
        int before = marks.byChunk.size();
        Long2ByteMaps.fastForEach(reading.marks(), mark -> marks.putIfAbsent(mark.getLongKey(), mark.getByteValue()));
        if (marks.byChunk.size() != before) {
            revision++;
        }
    }

    // A written mark the file lost was cleared by another game.
    private static void takeWhole(Marks marks, Long2ByteOpenHashMap file) {
        LongArrayList kept = new LongArrayList();
        for (int i = 0; i < marks.written; i++) {
            if (file.containsKey(marks.made.getLong(i))) {
                kept.add(marks.made.getLong(i));
            }
        }
        int written = kept.size();
        for (int i = marks.written; i < marks.made.size(); i++) {
            long chunk = marks.made.getLong(i);
            if (!file.containsKey(chunk)) {
                file.put(chunk, marks.byChunk.get(chunk));
                kept.add(chunk);
            }
        }
        marks.made = kept;
        marks.written = written;
        marks.replace(file);
    }

    private static String lines(Marks marks, int from, int upTo) {
        StringBuilder text = new StringBuilder();
        for (int i = from; i < upTo; i++) {
            long chunk = marks.made.getLong(i);
            byte letter = marks.byChunk.get(chunk);
            if (letter != 0) {
                line(text, chunk, letter);
            }
        }
        return text.toString();
    }

    private static String lines(Long2ByteOpenHashMap marks) {
        StringBuilder text = new StringBuilder();
        Long2ByteMaps.fastForEach(marks, mark -> line(text, mark.getLongKey(), mark.getByteValue()));
        return text.toString();
    }

    private static void line(StringBuilder text, long chunk, byte letter) {
        text.append(ChunkPos.getX(chunk)).append(' ').append(ChunkPos.getZ(chunk)).append(' ').append((char) letter)
            .append('\n');
    }

    // Only compact whilst the shared lock is held.
    private static Reading wholeFile(Path file, boolean compact) throws IOException {
        if (!Files.exists(file)) {
            return new Reading(null, 0, new Long2ByteOpenHashMap(), true);
        }
        String text = Files.readString(file, StandardCharsets.US_ASCII);
        int end = text.lastIndexOf('\n') + 1;
        Long2ByteOpenHashMap marks = new Long2ByteOpenHashMap();
        int lines = parse(text, end, marks);
        if (compact && lines - marks.size() > SPARE_LINES) {
            return rewrite(file, marks);
        }
        return new Reading(nameIn(text), end, marks, true);
    }

    private static Reading readAfter(Path file, String name, long end) throws IOException {
        if (!Files.exists(file)) {
            return new Reading(null, 0, new Long2ByteOpenHashMap(), true);
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            String head = read(channel, 0, Math.min(size, HEADER_BYTES));
            if (size < end || !Objects.equals(nameIn(head), name)) {
                return wholeFile(file, false);
            }
            Long2ByteOpenHashMap marks = new Long2ByteOpenHashMap();
            if (size == end) {
                return new Reading(name, end, marks, false);
            }
            String text = read(channel, end, size);
            int whole = text.lastIndexOf('\n') + 1;
            parse(text, whole, marks);
            return new Reading(name, end + whole, marks, false);
        }
    }

    // Only call it whilst the shared lock is held.
    private static Reading append(Path file, String lines, String name, long end, boolean follows)
        throws IOException {
        Reading before = follows ? readAfter(file, name, end) : null;
        String fresh = Files.exists(file) ? null : newName();
        Files.createDirectories(file.getParent());
        Files.writeString(file, fresh == null ? lines : HEADER + fresh + '\n' + lines, StandardCharsets.US_ASCII,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        long size = Files.size(file);
        if (before == null) {
            return new Reading(name, size, new Long2ByteOpenHashMap(), false);
        }
        if (!before.whole()) {
            return new Reading(before.name(), size, before.marks(), false);
        }
        parse(lines, lines.length(), before.marks());
        return new Reading(fresh == null ? before.name() : fresh, size, before.marks(), true);
    }

    // Only call it whilst the shared lock is held.
    private static Reading rewrite(Path file, Long2ByteOpenHashMap marks) throws IOException {
        String fresh = newName();
        String text = HEADER + fresh + '\n' + lines(marks);
        if (!DataFiles.writeSafely(file, temp -> Files.writeString(temp, text, StandardCharsets.US_ASCII))) {
            throw new IOException("Could not write " + file.getFileName());
        }
        return new Reading(fresh, text.length(), marks, true);
    }

    // Only call it whilst the shared lock is held.
    private static Reading emptied(Path file) throws IOException {
        return Files.exists(file) ? rewrite(file, new Long2ByteOpenHashMap()) : null;
    }

    private static int parse(String text, int upTo, Long2ByteOpenHashMap into) {
        Matcher line = MARK.matcher(text);
        int count = 0;
        int start = 0;
        while (start < upTo) {
            int stop = text.indexOf('\n', start);
            if (stop < 0 || stop > upTo) {
                break;
            }
            line.region(start, stop);
            if (line.matches()) {
                try {
                    long chunk = ChunkPos.pack(Integer.parseInt(line.group(1)), Integer.parseInt(line.group(2)));
                    into.putIfAbsent(chunk, (byte) line.group(3).charAt(0));
                    count++;
                } catch (NumberFormatException e) {
                    // A number too long for a chunk is a broken line and is skipped.
                }
            }
            start = stop + 1;
        }
        return count;
    }

    private static String nameIn(String text) {
        if (!text.startsWith(HEADER)) {
            return null;
        }
        int stop = text.indexOf('\n');
        return stop < 0 ? null : text.substring(HEADER.length(), stop).trim();
    }

    private static String newName() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    private static String read(FileChannel channel, long from, long to) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate((int) (to - from));
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, from + buffer.position()) < 0) {
                break;
            }
        }
        return new String(buffer.array(), 0, buffer.position(), StandardCharsets.US_ASCII);
    }

    private static void saveAllOnExit() {
        for (ChunkMarks store : ALL) {
            if (!store.saving || store.server == null) {
                continue;
            }
            for (Map.Entry<String, Marks> entry : store.dimensions.entrySet()) {
                Marks marks = entry.getValue();
                if (marks.written >= marks.made.size()) {
                    continue;
                }
                String lines = lines(marks, marks.written, marks.made.size());
                Path file = store.file(entry.getKey());
                try {
                    SharedFiles.locked(() -> append(file, lines, null, 0, false));
                } catch (IOException | RuntimeException e) {
                    OfflineClient.LOG.error("Could not save the chunk marks of {}", entry.getKey(), e);
                }
            }
        }
    }
}
