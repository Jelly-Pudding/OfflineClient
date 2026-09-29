package com.jellypudding.offlineclient.config;

import com.jellypudding.offlineclient.OfflineClient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// Everything the client saves lives in the offlineclient folder of the game directory.
public final class DataFiles {

    private static final String FOLDER = "offlineclient";
    private static final String JSON = ".json";
    private static final String TEMP = ".tmp";
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

    // Empty when the file is missing. A file that cannot be read or decoded is logged
    // and comes back empty as well.
    public static <T> Optional<T> readJson(Path path, Function<JsonElement, T> decoder) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(decoder.apply(JsonParser.parseString(Files.readString(path))));
        } catch (IOException | RuntimeException e) {
            OfflineClient.LOG.error("Failed to read {}", path.getFileName(), e);
            return Optional.empty();
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
    // corrupt the saved file. False after logging why the file could not be written.
    public static boolean writeSafely(Path path, Contents contents) {
        Path temp = path.resolveSibling(path.getFileName() + TEMP);
        try {
            Files.createDirectories(path.getParent());
            contents.writeTo(temp);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            OfflineClient.LOG.error("Failed to save {}", path.getFileName(), e);
            return false;
        }
    }
}
