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

// A letter for every chunk a module judged. Each server and dimension keeps its own. The
// first letter a chunk gets stays. With saving on each new mark goes on the end of a text
// file for its dimension. With loading on that file comes back on the next visit and what
// another game on this computer adds to it turns up within seconds. Modules call it on the
// game thread and the files are read and written on the shared file thread.
public final class ChunkMarks {

    // What a look at a file found. A whole reading holds every mark in the file because it was
    // read for the first time or was written anew since. Otherwise it holds the lines another
    // game added past the end this game had reached. The name is null for a file with none.
    record Reading(String name, long end, Long2ByteOpenHashMap marks, boolean whole) {
    }

    private static final String FOLDER = "chunks";
    private static final String TEXT = ".txt";
    // The first line of a file holds a name that changes whenever the file is written anew.
    private static final String HEADER = "# chunk marks ";
    // The header name is short. This much of the file always holds it.
    private static final int HEADER_BYTES = 64;
    // A mark per line as x and z and a letter.
    private static final Pattern MARK = Pattern.compile("(-?\\d+) (-?\\d+) (\\p{Lower})");
    // Marks are lower case letters and the counts are kept by letter.
    private static final int LETTERS = 128;

    // New marks wait this long for others before they are written.
    private static final long WRITE_GAP_MS = 3000;
    // How often the file of the dimension you are in is checked for what another game added.
    private static final long POLL_MS = 5000;
    // A file this many lines longer than the chunks it holds is written again without repeats.
    private static final int SPARE_LINES = 20_000;

    private static final List<ChunkMarks> ALL = new CopyOnWriteArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ChunkMarks::saveAllOnExit, "OfflineClient chunk marks save"));
    }

    // The marks of one dimension and how far this game has come with its file.
    private static final class Marks {
        private Long2ByteOpenHashMap byChunk = new Long2ByteOpenHashMap();
        private final int[] counts = new int[LETTERS];
        // Every mark this game made here in order. The first ones up to written are in the file.
        private LongArrayList made = new LongArrayList();
        private int written;
        // The name in the first line of the file and how far into it this game has read.
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

    // The marks of the server you play on by dimension.
    private final Map<String, Marks> dimensions = new HashMap<>();
    // The server the marks belong to. Null before the first world.
    private String server;
    // Moves on with every new server. An answer that comes back for an older one is dropped.
    private int generation;
    private boolean saving;
    private boolean loading;
    private int revision;

    // Loading only reads files whilst the module is on.
    public ChunkMarks(Module owner) {
        this.owner = owner;
        ALL.add(this);
        OfflineClient.INSTANCE.getEventBus().register(this);
    }

    public Setting<?>[] settings() {
        return new Setting<?>[] {save, load};
    }

    // Goes up whenever the marks change. A module rebuilds what it draws only when this moves.
    public int revision() {
        return revision;
    }

    // The letter of a chunk in the dimension you are in. Nought when it has none.
    public byte get(long chunk) {
        Marks marks = current(false);
        return marks == null ? 0 : marks.byChunk.get(chunk);
    }

    // Gives a chunk in the dimension you are in its letter. False when it already had one.
    // That one stays.
    public boolean mark(long chunk, byte letter) {
        Marks marks = current(true);
        if (marks == null || !marks.putIfAbsent(chunk, letter)) {
            return false;
        }
        marks.made.add(chunk);
        revision++;
        return true;
    }

    // The marks of the dimension you are in as a view for drawing.
    public Long2ByteMap here() {
        Marks marks = current(false);
        return marks == null ? Long2ByteMaps.EMPTY_MAP : Long2ByteMaps.unmodifiable(marks.byChunk);
    }

    // How many chunks of the dimension you are in carry the letter.
    public int count(byte letter) {
        Marks marks = current(false);
        return marks == null ? 0 : marks.counts[letter];
    }

    // Forgets the marks of the dimension you are in and empties its file. Other games that
    // load the file forget them too. Hands back how many there were.
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

    // Writes what waits and forgets the marks of every dimension. Loading brings the saved
    // ones back.
    public void forget() {
        flushAll(true);
        dimensions.clear();
        revision++;
    }

    // The marks of the dimension you are in. Null outside a world.
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

    // The marks follow the server you play on. A new one writes what the last was waiting
    // for and starts again from its own files.
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

    // A switch flipped whilst you play acts at once. Saving writes every mark made whilst it
    // was off. Loading reads the files again or drops every mark that came from them.
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

    // Writes what waits in every dimension without the usual pause. Leaving a server or
    // forgetting the marks writes even what a write under way may already hold because the
    // marks are dropped after.
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

    // Takes in what the file held. A whole reading replaces what came from the file before
    // and the first mark a chunk ever got wins. Lines another game added only fill gaps.
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

    // The file becomes the marks. This game keeps what it has not written yet unless the file
    // already has that chunk. A written mark the file lost was cleared by another game.
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

    // The lines of the marks made from one point to another.
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

    // One mark as x and z and its letter.
    private static void line(StringBuilder text, long chunk, byte letter) {
        text.append(ChunkPos.getX(chunk)).append(' ').append(ChunkPos.getZ(chunk)).append(' ').append((char) letter)
            .append('\n');
    }

    // Every mark in the file up to its last whole line. A file that is not there reads empty.
    // Compacting first writes a file again that holds far more lines than chunks. Only
    // compact whilst the shared lock is held.
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

    // What another game added past the end this game reached. The whole file again when it
    // was written anew since or is gone.
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

    // Puts the lines at the end of the file and starts it with a name when it is new. A game
    // that follows the file first reads what another game added. Only call it whilst the
    // shared lock is held.
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
        // The file is new or was written anew and these lines belong to it now as well.
        parse(lines, lines.length(), before.marks());
        return new Reading(fresh == null ? before.name() : fresh, size, before.marks(), true);
    }

    // Writes the file anew under a new name. Only call it whilst the shared lock is held.
    private static Reading rewrite(Path file, Long2ByteOpenHashMap marks) throws IOException {
        String fresh = newName();
        String text = HEADER + fresh + '\n' + lines(marks);
        if (!DataFiles.writeSafely(file, temp -> Files.writeString(temp, text, StandardCharsets.US_ASCII))) {
            throw new IOException("Could not write " + file.getFileName());
        }
        return new Reading(fresh, text.length(), marks, true);
    }

    // Empties a file under a new name. Only call it whilst the shared lock is held.
    private static Reading emptied(Path file) throws IOException {
        return Files.exists(file) ? rewrite(file, new Long2ByteOpenHashMap()) : null;
    }

    // Reads the whole lines before the given point into the marks. A chunk already there
    // keeps its letter. Hands back how many lines held a mark.
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

    // The name on the first line or null when the text does not start with one.
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

    // Writes what every store still holds unwritten as the game closes.
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
