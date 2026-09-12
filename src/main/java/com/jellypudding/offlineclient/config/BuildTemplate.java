package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

// A shape saved as a list of block offsets in offlineclient/templates. Positions
// run sideways then up then forward from the block you build on. A block name
// beside an offset asks for that block and no name means whatever you hold.
public final class BuildTemplate {

    // One block of the shape. A null block means any block will do.
    public record Entry(int x, int y, int z, Block block) {
    }

    private static final String EXTENSION = ".json";
    private static final int VERSION = 2;

    // The shapes written out the first time the folder is empty.
    private static final Map<String, int[][]> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("Bridge", new int[][] {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}, {-1, 0, 1},
            {-1, 0, 0}, {-1, 0, 2}, {0, 0, 2}, {1, 0, 2}, {1, 0, 3}, {0, 0, 3}, {-1, 0, 3},
            {-1, 0, 4}, {0, 0, 4}, {1, 0, 4}, {1, 0, 5}, {0, 0, 5}, {-1, 0, 5}});
        DEFAULTS.put("Pillar", new int[][] {{0, 0, 0}, {0, 1, 0}, {0, 2, 0}, {0, 3, 0}, {0, 4, 0},
            {0, 5, 0}, {0, 6, 0}});
        DEFAULTS.put("Floor", square(3, 0));
        DEFAULTS.put("Wall", wall(7, 7));
        DEFAULTS.put("Tower", tower(2, 6));
    }

    private final String name;
    private final List<Entry> entries;

    private BuildTemplate(String name, List<Entry> entries) {
        this.name = name;
        this.entries = List.copyOf(entries);
    }

    public String name() {
        return name;
    }

    public int size() {
        return entries.size();
    }

    public static Path folder() {
        return OfflineClient.MC.gameDirectory.toPath().resolve("offlineclient").resolve("templates");
    }

    // The names on offer with the defaults written out on the first look.
    public static Collection<String> names() {
        List<String> names = new ArrayList<>();
        try {
            Files.createDirectories(folder());
            writeDefaults();
            try (Stream<Path> files = Files.list(folder())) {
                files.filter(path -> path.toString().endsWith(EXTENSION))
                    .map(path -> path.getFileName().toString())
                    .map(file -> file.substring(0, file.length() - EXTENSION.length()))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(names::add);
            }
        } catch (IOException error) {
            OfflineClient.LOG.error("Failed to list the templates", error);
        }
        return names;
    }

    private static void writeDefaults() throws IOException {
        try (Stream<Path> files = Files.list(folder())) {
            if (files.findAny().isPresent()) {
                return;
            }
        }
        for (Map.Entry<String, int[][]> shape : DEFAULTS.entrySet()) {
            List<Entry> entries = new ArrayList<>();
            for (int[] at : shape.getValue()) {
                entries.add(new Entry(at[0], at[1], at[2], null));
            }
            save(shape.getKey(), entries);
        }
    }

    // Null when the file is missing or unreadable.
    public static BuildTemplate load(String name) {
        Path path = folder().resolve(name + EXTENSION);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            List<Entry> entries = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("blocks")) {
                entries.add(element.isJsonArray() ? plainEntry(element.getAsJsonArray())
                    : namedEntry(element.getAsJsonObject()));
            }
            return entries.isEmpty() ? null : new BuildTemplate(name, entries);
        } catch (Exception error) {
            OfflineClient.LOG.error("Failed to read the template {}", name, error);
            return null;
        }
    }

    // The old form is a bare list of three numbers.
    private static Entry plainEntry(JsonArray at) {
        return new Entry(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt(), null);
    }

    private static Entry namedEntry(JsonObject object) {
        JsonArray at = object.getAsJsonArray("pos");
        Block block = null;
        if (object.has("block") && !object.get("block").getAsString().isEmpty()) {
            Identifier id = Identifier.tryParse(object.get("block").getAsString());
            block = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
            if (block == Blocks.AIR) {
                block = null;
            }
        }
        return new Entry(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt(), block);
    }

    public static void save(String name, List<Entry> entries) throws IOException {
        JsonArray blocks = new JsonArray();
        for (Entry entry : entries) {
            JsonObject block = new JsonObject();
            JsonArray at = new JsonArray();
            at.add(entry.x());
            at.add(entry.y());
            at.add(entry.z());
            block.add("pos", at);
            block.addProperty("block", entry.block() == null ? ""
                : BuiltInRegistries.BLOCK.getKey(entry.block()).toString());
            blocks.add(block);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.add("blocks", blocks);
        Files.createDirectories(folder());
        ConfigManager.write(folder().resolve(name + EXTENSION), root);
    }

    // Every block of the shape laid out from the origin in the facing given.
    // Insertion order is kept. A template is built in the order it was written.
    public Map<BlockPos, Block> layOut(BlockPos origin, Direction front) {
        Direction left = front.getCounterClockWise();
        Map<BlockPos, Block> placed = new LinkedHashMap<>();
        for (Entry entry : entries) {
            BlockPos pos = origin.relative(front, entry.z()).relative(left, entry.x()).above(entry.y());
            placed.put(pos, entry.block());
        }
        return placed;
    }

    private static int[][] square(int radius, int y) {
        List<int[]> out = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = 0; z <= radius * 2; z++) {
                out.add(new int[] {x, y, z});
            }
        }
        return out.toArray(new int[0][]);
    }

    private static int[][] wall(int width, int height) {
        List<int[]> out = new ArrayList<>();
        int half = width / 2;
        for (int y = 0; y < height; y++) {
            for (int x = -half; x <= half; x++) {
                out.add(new int[] {x, y, 0});
            }
        }
        return out.toArray(new int[0][]);
    }

    // A hollow square tube.
    private static int[][] tower(int radius, int height) {
        List<int[]> out = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = 0; z <= radius * 2; z++) {
                    boolean edge = Math.abs(x) == radius || z == 0 || z == radius * 2;
                    if (edge) {
                        out.add(new int[] {x, y, z});
                    }
                }
            }
        }
        return out.toArray(new int[0][]);
    }
}
