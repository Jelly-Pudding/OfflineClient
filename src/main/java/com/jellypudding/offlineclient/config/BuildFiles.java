package com.jellypudding.offlineclient.config;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.Background;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.stream.Stream;

// A file is told apart by what it holds and not by its ending. Each one is read once off the game
// thread and kept until the files are forgotten.
public final class BuildFiles {

    public static final class Unreadable extends Exception {

        Unreadable(String reason) {
            super(reason);
        }
    }

    // The names are the blocks this game does not have. Their cells are left alone.
    public record Loaded(Blueprint blueprint, Set<String> leftOut) {
    }

    // One of the two is null.
    public record Result(Loaded loaded, String problem) {
    }

    // The numbered format from before the game named its blocks.
    private static final String NUMBERED_EXTENSION = ".schematic";
    private static final String NUMBERED_KEY = "Materials";

    private static final List<String> EXTENSIONS = List.of(SpongeSchematic.EXTENSION, Litematic.EXTENSION,
        StructureNbt.EXTENSION, NUMBERED_EXTENSION);

    // The game counts every tag as it reads. A small file that claims to unpack to thousands of
    // millions of bytes is refused before it costs anything.
    private static final long MAX_UNPACKED_BYTES = 256L << 20;
    private static final int BYTES_PER_MEGABYTE = 1 << 20;
    // How deep the game itself lets tags nest.
    private static final int MAX_TAG_DEPTH = 512;

    // The game throws the same thing for tags nested too deep and that is no matter of size.
    private static final class Room extends NbtAccounter {

        private boolean ranOut;

        private Room() {
            super(MAX_UNPACKED_BYTES, MAX_TAG_DEPTH);
        }

        @Override
        public void accountBytes(long bytes) {
            try {
                super.accountBytes(bytes);
            } catch (NbtAccounterException e) {
                ranOut = true;
                throw e;
            }
        }
    }

    private static final ExecutorService READER = Background.worker("OfflineClient build files");

    // Touched on the game thread only. A null result is a read still under way.
    private static final Map<String, Result> RESULTS = new HashMap<>();
    private static final Map<String, List<Consumer<Result>>> WAITING = new HashMap<>();
    private static final Consumer<Result> NOBODY = result -> {
    };

    private BuildFiles() {
    }

    public static Path folder() {
        return DataFiles.path("schematics");
    }

    public static List<String> names() {
        return names(folder());
    }

    public static List<String> names(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile).map(file -> file.getFileName().toString())
                .filter(BuildFiles::isBuildFile).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        } catch (IOException e) {
            OfflineClient.LOG.warn("Cannot list {}", folder.getFileName(), e);
            return List.of();
        }
    }

    private static boolean isBuildFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    public static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    // Null whilst it is being read and for a file that cannot be. The first ask starts the read.
    public static Loaded loaded(String fileName) {
        Result result = RESULTS.get(fileName);
        if (result == null) {
            read(fileName, NOBODY);
            return null;
        }
        return result.loaded();
    }

    public static Blueprint ready(String fileName) {
        Loaded loaded = loaded(fileName);
        return loaded == null ? null : loaded.blueprint();
    }

    public static String problem(String fileName) {
        Result result = RESULTS.get(fileName);
        return result == null ? null : result.problem();
    }

    public static void read(String fileName, Consumer<Result> then) {
        Result known = RESULTS.get(fileName);
        if (known != null) {
            then.accept(known);
            return;
        }
        List<Consumer<Result>> waiting = WAITING.get(fileName);
        if (waiting != null) {
            waiting.add(then);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(then);
        WAITING.put(fileName, waiting);
        Path folder = folder();
        READER.execute(() -> {
            Result result = load(fileName, folder);
            OfflineClient.MC.execute(() -> finish(fileName, result));
        });
    }

    // Nothing is kept and each call reads the file afresh.
    public static void readOnce(Path folder, String fileName, Consumer<Result> then) {
        READER.execute(() -> {
            Result result = load(fileName, folder);
            OfflineClient.MC.execute(() -> then.accept(result));
        });
    }

    private static void finish(String fileName, Result result) {
        RESULTS.put(fileName, result);
        List<Consumer<Result>> waiting = WAITING.remove(fileName);
        if (waiting != null) {
            waiting.forEach(then -> then.accept(result));
        }
    }

    public static void forget() {
        RESULTS.clear();
    }

    // Whatever goes wrong comes back as words and running out of memory does too.
    static Result load(String fileName, Path folder) {
        try {
            return new Result(parse(stem(fileName), inFolder(fileName, folder)), null);
        } catch (Unreadable e) {
            return new Result(null, fileName + " " + e.getMessage() + ".");
        } catch (OutOfMemoryError e) {
            return new Result(null, fileName + " is too big for the memory the game has left.");
        } catch (Throwable e) {
            OfflineClient.LOG.error("Failed to read {}", fileName, e);
            return new Result(null, fileName + " could not be read. The log says why.");
        }
    }

    // A name that climbs out of the folder is no file of it.
    private static Path inFolder(String fileName, Path folder) throws Unreadable {
        Path file = DataFiles.inside(folder, fileName);
        if (file == null || !Files.isRegularFile(file)) {
            throw new Unreadable("is not in the " + folder.getFileName() + " folder");
        }
        return file;
    }

    static Loaded parse(String name, Path file) throws Unreadable {
        CompoundTag root;
        Room room = new Room();
        try {
            root = NbtIo.readCompressed(file, room);
        } catch (IOException | RuntimeException e) {
            throw new Unreadable(room.ranOut ? "is too big. It unpacks to more than "
                + MAX_UNPACKED_BYTES / BYTES_PER_MEGABYTE + " megabytes" : "is not a build file");
        }
        SavedStates states;
        Blueprint blueprint;
        if (SpongeSchematic.holds(root)) {
            states = new SavedStates(SpongeSchematic.dataVersion(root));
            blueprint = SpongeSchematic.read(name, root, states);
        } else if (Litematic.holds(root)) {
            states = new SavedStates(Litematic.dataVersion(root));
            blueprint = Litematic.read(name, root, states);
        } else if (StructureNbt.holds(root)) {
            states = new SavedStates(StructureNbt.dataVersion(root));
            blueprint = StructureNbt.read(name, root, states);
        } else if (root.contains(NUMBERED_KEY)) {
            throw new Unreadable("keeps its blocks as numbers the game has dropped. Save it again as a "
                + SpongeSchematic.EXTENSION + " or a " + Litematic.EXTENSION + " file");
        } else {
            throw new Unreadable("is not a build file");
        }
        if (blueprint.blocks() == 0) {
            throw new Unreadable("holds no blocks this game has");
        }
        return new Loaded(blueprint, states.unknown());
    }

    static void checkSize(long sizeX, long sizeY, long sizeZ) throws Unreadable {
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
            throw new Unreadable("holds no blocks");
        }
        if (!Blueprint.Builder.fits(sizeX, sizeY, sizeZ)) {
            throw new Unreadable("is " + sizeX + " by " + sizeY + " by " + sizeZ + " blocks. One build holds at most "
                + Blueprint.MAX_CELLS);
        }
    }
}
