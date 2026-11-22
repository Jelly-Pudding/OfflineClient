package com.jellypudding.offlineclient.config;

import com.jellypudding.offlineclient.OfflineClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Consumer;

// A printed file moves into the finished folder inside.
public final class MapFiles {

    private static final String FOLDER = "mapart";
    private static final String FINISHED = "finished";

    private MapFiles() {
    }

    public static Path folder() {
        return DataFiles.path(FOLDER);
    }

    public static String shown() {
        return "offlineclient/" + FOLDER;
    }

    public static List<String> waiting() {
        return BuildFiles.names(folder());
    }

    public static void makeFolder() {
        try {
            Files.createDirectories(folder());
        } catch (IOException e) {
            OfflineClient.LOG.error("Cannot make {}", FOLDER, e);
        }
    }

    // Reads a waiting file off the game thread. The answer comes on the game thread.
    public static void read(String fileName, Consumer<BuildFiles.Result> then) {
        BuildFiles.readOnce(folder(), fileName, then);
    }

    public static boolean finish(String fileName) {
        Path root = folder().toAbsolutePath().normalize();
        Path file = root.resolve(fileName).normalize();
        if (!root.equals(file.getParent())) {
            OfflineClient.LOG.error("Cannot move {} into the finished folder as it is no file of {}", fileName, root);
            return false;
        }
        try {
            Path finished = Files.createDirectories(root.resolve(FINISHED));
            Files.move(file, finished.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            OfflineClient.LOG.error("Cannot move {} into the finished folder", fileName, e);
            return false;
        }
    }
}
