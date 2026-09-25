package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// A shape saved as a list of block offsets in offlineclient/templates. Positions
// run sideways then up then forward from the block you build on. A block name
// beside an offset asks for that block and no name means whatever you hold.
public final class BuildTemplate {

    // A null block means any block will do.
    public record Entry(int x, int y, int z, Block block) {
    }

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
        return DataFiles.path("templates");
    }

    // The names on offer with the defaults written out on the first look.
    public static Collection<String> names() {
        List<String> names = DataFiles.jsonNames(folder());
        if (!names.isEmpty()) {
            return names;
        }
        writeDefaults();
        return DataFiles.jsonNames(folder());
    }

    private static void writeDefaults() {
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
        return DataFiles.readJson(DataFiles.jsonFile(folder(), name), root -> {
            List<Entry> entries = new ArrayList<>();
            for (JsonElement element : root.getAsJsonObject().getAsJsonArray("blocks")) {
                entries.add(element.isJsonArray() ? plainEntry(element.getAsJsonArray())
                    : namedEntry(element.getAsJsonObject()));
            }
            return entries.isEmpty() ? null : new BuildTemplate(name, entries);
        }).orElse(null);
    }

    // An entry can also be a bare list of three numbers.
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

    // False when the file could not be written.
    public static boolean save(String name, List<Entry> entries) {
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
        return DataFiles.writeJson(DataFiles.jsonFile(folder(), name), root);
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
