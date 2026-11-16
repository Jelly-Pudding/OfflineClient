package com.jellypudding.offlineclient.config;

import com.jellypudding.offlineclient.OfflineClient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// Everything the client saves lives in the offlineclient folder of the game directory.
public final class DataFiles {

    private static final String FOLDER = "offlineclient";
    private static final String JSON = ".json";
    private static final String TEMP = ".tmp";
    // A file that cannot be read is kept beside the new one under this ending.
    private static final String BROKEN = ".broken";
    // How often a refused swap is tried and the wait before each new go.
    private static final int SWAP_TRIES = 5;
    private static final long SWAP_WAIT_NANOS = 20_000_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern UNSAFE = Pattern.compile("[^a-zA-Z0-9_.-]");
    // How much of a key's hash tells two keys with the same safe name apart.
    private static final int KEY_HASH_DIGITS = 8;
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder()
        .append(DateTimeFormatter.ISO_LOCAL_DATE).appendLiteral(' ').append(DateTimeFormatter.ISO_LOCAL_TIME)
        .toFormatter();

    // Fills the file it is handed.
    @FunctionalInterface
    public interface Contents {
        void writeTo(Path file) throws IOException;
    }

    private DataFiles() {
    }

    // The client folder itself or a path inside it.
    public static Path path(String... parts) {
        Path path = OfflineClient.MC.gameDirectory.toPath().resolve(FOLDER);
        for (String part : parts) {
            path = path.resolve(part);
        }
        return path;
    }

    public static Path jsonFile(Path folder, String name) {
        return folder.resolve(name + JSON);
    }

    // The file a player names inside the folder. Null for a name no path can hold or one that ends at
    // the folder itself such as an empty name. Null too for one that leads out of the folder by
    // climbing above it or by naming a place elsewhere in full.
    public static Path inside(Path folder, String name) {
        Path root = folder.toAbsolutePath().normalize();
        try {
            Path path = root.resolve(name).normalize();
            return path.startsWith(root) && !path.equals(root) ? path : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    // Anything a file name could not hold on every system becomes an underscore.
    public static String safeName(String name) {
        return UNSAFE.matcher(name).replaceAll("_");
    }

    // The folder or file name for a server or world key. A key the safe name changed gets a
    // short hash of itself as well. Two worlds whose names differ only in such signs then
    // keep files of their own.
    public static String keyName(String key) {
        String safe = safeName(key);
        if (safe.equals(key)) {
            return safe;
        }
        String hash = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        return safe + "-" + hash.substring(0, KEY_HASH_DIGITS);
    }

    // The names of the json files in a folder from A to Z. Empty when the folder is missing.
    public static List<String> jsonNames(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> file.getFileName().toString())
                .filter(file -> file.endsWith(JSON))
                .map(file -> file.substring(0, file.length() - JSON.length()))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        } catch (IOException e) {
            OfflineClient.LOG.warn("Cannot list {}", folder.getFileName(), e);
            return List.of();
        }
    }

    // Every entry of a saved list read on its own. An entry whose read throws or gives null is
    // left out and the log says how many went. A value that is no list throws.
    public static <T> List<T> entries(JsonElement list, Function<JsonElement, T> read, String what) {
        List<T> kept = new ArrayList<>();
        int broken = 0;
        for (JsonElement entry : list.getAsJsonArray()) {
            T value = attempt(() -> read.apply(entry));
            if (value == null) {
                broken++;
            } else {
                kept.add(value);
            }
        }
        leftOut(broken, what);
        return kept;
    }

    // The same for the members of a saved object. Each is read with its name and kept under it
    // in the order the file holds them. A value that is no object throws.
    public static <T> Map<String, T> members(JsonElement object, BiFunction<String, JsonElement, T> read,
                                             String what) {
        Map<String, T> kept = new LinkedHashMap<>();
        int broken = 0;
        for (Map.Entry<String, JsonElement> member : object.getAsJsonObject().entrySet()) {
            T value = attempt(() -> read.apply(member.getKey(), member.getValue()));
            if (value == null) {
                broken++;
            } else {
                kept.put(member.getKey(), value);
            }
        }
        leftOut(broken, what);
        return kept;
    }

    // Null for a read that throws.
    private static <T> T attempt(Supplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void leftOut(int broken, String what) {
        if (broken > 0) {
            OfflineClient.LOG.warn("Left out {} {} that could not be read", broken, what);
        }
    }

    // Keeps a file that cannot be read under a name of its own for the player to mend. A new
    // file then takes its place. False after logging why it could not be moved.
    public static boolean setAside(Path path) {
        Path aside = path.resolveSibling(path.getFileName() + BROKEN);
        try {
            Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            OfflineClient.LOG.error("Cannot move the unreadable {} aside", path.getFileName(), e);
            return false;
        }
        OfflineClient.LOG.warn("Moved the unreadable {} to {}", path.getFileName(), aside);
        return true;
    }

    // Empty when the file is missing. A file that cannot be read or decoded is logged
    // and comes back empty as well.
    public static <T> Optional<T> readJson(Path path, Function<JsonElement, T> decoder) {
        Optional<String> text = readText(path);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(decoder.apply(JsonParser.parseString(text.get())));
        } catch (RuntimeException e) {
            OfflineClient.LOG.error("Failed to read {}", path.getFileName(), e);
            return Optional.empty();
        }
    }

    // The whole text of a file. Empty when the file is missing. A file that cannot be read is
    // logged and comes back empty as well.
    public static Optional<String> readText(Path path) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(path));
        } catch (IOException e) {
            OfflineClient.LOG.error("Failed to read {}", path.getFileName(), e);
            return Optional.empty();
        }
    }

    // The same for at most that many characters from the start. The rest of a longer file is
    // never read.
    public static Optional<String> readText(Path path, int most) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try (Reader in = Files.newBufferedReader(path)) {
            char[] text = new char[most];
            int read = 0;
            int got;
            while (read < most && (got = in.read(text, read, most - read)) != -1) {
                read += got;
            }
            return Optional.of(new String(text, 0, read));
        } catch (IOException e) {
            OfflineClient.LOG.error("Failed to read {}", path.getFileName(), e);
            return Optional.empty();
        }
    }

    // The bytes of a file of at most that many. Null for a larger file and no more than one byte
    // past the bound is read.
    public static byte[] readAtMost(Path path, int most) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(most + 1);
            return bytes.length > most ? null : bytes;
        }
    }

    public static boolean writeJson(Path path, JsonElement root) {
        return writeSafely(path, temp -> Files.writeString(temp, GSON.toJson(root)));
    }

    // The date and time to the second. A line added to a text file starts with it.
    public static String timestamp() {
        return timestamp(System.currentTimeMillis());
    }

    // The same for a moment in milliseconds.
    public static String timestamp(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
            .truncatedTo(ChronoUnit.SECONDS).format(TIMESTAMP);
    }

    // Adds the lines to the end of a text file. The file and its folders are made when missing.
    // False after logging why the lines could not be written.
    public static boolean appendLines(Path path, List<String> lines) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            OfflineClient.LOG.error("Failed to add to {}", path.getFileName(), e);
            return false;
        }
    }

    // Fills a file beside the target that then takes its place. A crash mid write cannot
    // corrupt the saved file. One save at a time has the file beside the target to itself.
    // False after logging why the file could not be written.
    public static synchronized boolean writeSafely(Path path, Contents contents) {
        Path temp = path.resolveSibling(path.getFileName() + TEMP);
        try {
            Files.createDirectories(path.getParent());
            contents.writeTo(temp);
            swapIn(temp, path);
            return true;
        } catch (IOException e) {
            OfflineClient.LOG.error("Failed to save {}", path.getFileName(), e);
            return false;
        }
    }

    // Windows refuses the swap for as long as anything else has the old file open and a virus
    // scanner opens every file that was just written. It lets go within moments and the swap
    // is tried again.
    private static void swapIn(Path temp, Path path) throws IOException {
        for (int tries = 1; ; tries++) {
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (FileSystemException held) {
                boolean hopeless = held instanceof NoSuchFileException
                    || held instanceof AtomicMoveNotSupportedException;
                if (hopeless || tries == SWAP_TRIES) {
                    throw held;
                }
                LockSupport.parkNanos(SWAP_WAIT_NANOS);
            }
        }
    }
}
