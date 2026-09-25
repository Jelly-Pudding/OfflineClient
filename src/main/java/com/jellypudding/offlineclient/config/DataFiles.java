package com.jellypudding.offlineclient.config;

import com.jellypudding.offlineclient.OfflineClient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

// Everything the client saves lives in the offlineclient folder of the game directory.
public final class DataFiles {

    private static final String FOLDER = "offlineclient";
    private static final String JSON = ".json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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

    // Written to a temporary copy first. A crash mid write cannot corrupt the saved file.
    // False when the file could not be written.
    public static boolean writeJson(Path path, JsonElement root) {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temp, GSON.toJson(root));
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            OfflineClient.LOG.error("Failed to save {}", path.getFileName(), e);
            return false;
        }
    }
}
