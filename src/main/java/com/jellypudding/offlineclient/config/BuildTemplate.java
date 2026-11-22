package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Positions run sideways to the left then up then forward from the block you build on as seen by
// someone facing south. A saved file wins over a shape of the same name that came with the client.
public final class BuildTemplate {

    // A null state means any block will do.
    public record Entry(int x, int y, int z, BlockState state) {
    }

    private static final int VERSION = 3;

    private static final String VERSION_KEY = "version";
    private static final String DATA_VERSION_KEY = "data_version";
    private static final String BLOCKS_KEY = "blocks";
    private static final String POSITION_KEY = "pos";
    private static final String BLOCK_KEY = "block";

    // A nether portal frame without its corners. The two columns inside stand over the block you
    // build on and the one to its left.
    private static final int PORTAL_INSIDE_WIDTH = 2;
    private static final int PORTAL_INSIDE_HEIGHT = 3;

    private static final Map<String, List<Entry>> BUILT_IN = new LinkedHashMap<>();

    static {
        BUILT_IN.put("Bridge", box(-1, 1, 0, 0, 0, 5, null));
        BUILT_IN.put("Pillar", box(0, 0, 0, 6, 0, 0, null));
        BUILT_IN.put("Floor", box(-3, 3, 0, 0, 0, 6, null));
        BUILT_IN.put("Wall", box(-3, 3, 0, 6, 0, 0, null));
        BUILT_IN.put("Tower", tower(2, 6));
        BUILT_IN.put("Iron golem", golem(Blocks.IRON_BLOCK.defaultBlockState(), true));
        BUILT_IN.put("Snow golem", golem(Blocks.SNOW_BLOCK.defaultBlockState(), false));
        BUILT_IN.put("Portal", portal());
    }

    private BuildTemplate() {
    }

    public static Blueprint of(String name, List<Entry> entries) throws BuildFiles.Unreadable {
        if (entries.isEmpty()) {
            return null;
        }
        Blueprint.Box box = new Blueprint.Box();
        Set<BlockState> kinds = new HashSet<>();
        for (Entry entry : entries) {
            box.add(entry.x(), entry.y(), entry.z());
            kinds.add(entry.state());
        }
        BuildFiles.checkSize(box.sizeX(), box.sizeY(), box.sizeZ());
        Blueprint.Builder builder = new Blueprint.Builder(name, box.min(), box.size(), kinds.size());
        for (Entry entry : entries) {
            builder.set(entry.x(), entry.y(), entry.z(), builder.kind(entry.state()));
        }
        return builder.build();
    }

    public static Path folder() {
        return DataFiles.path("templates");
    }

    public static Collection<String> names() {
        Set<String> names = new LinkedHashSet<>(BUILT_IN.keySet());
        names.addAll(DataFiles.jsonNames(folder()));
        return names;
    }

    public static Blueprint load(String name) throws BuildFiles.Unreadable {
        Path file = DataFiles.jsonFile(folder(), name);
        List<Entry> entries = Files.isRegularFile(file)
            ? DataFiles.readJson(file, root -> entries(root.getAsJsonObject())).orElse(null) : BUILT_IN.get(name);
        return entries == null ? null : of(name, entries);
    }

    private static List<Entry> entries(JsonObject root) {
        SavedStates states = new SavedStates(root.has(DATA_VERSION_KEY) ? root.get(DATA_VERSION_KEY).getAsInt() : 0);
        List<Entry> entries = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray(BLOCKS_KEY)) {
            // An entry can also be a bare list of three numbers.
            JsonArray at = element.isJsonArray() ? element.getAsJsonArray()
                : element.getAsJsonObject().getAsJsonArray(POSITION_KEY);
            entries.add(new Entry(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt(),
                element.isJsonArray() ? null : state(element.getAsJsonObject(), states)));
        }
        return entries;
    }

    // No name or air or a block this game does not have all mean any block.
    private static BlockState state(JsonObject entry, SavedStates states) {
        String text = entry.has(BLOCK_KEY) ? entry.get(BLOCK_KEY).getAsString() : "";
        BlockState state = text.isEmpty() ? null : states.read(text);
        return state == null || state.isAir() ? null : state;
    }

    public static boolean save(String name, List<Entry> entries) {
        JsonArray blocks = new JsonArray();
        for (Entry entry : entries) {
            JsonObject block = new JsonObject();
            JsonArray at = new JsonArray();
            at.add(entry.x());
            at.add(entry.y());
            at.add(entry.z());
            block.add(POSITION_KEY, at);
            block.addProperty(BLOCK_KEY, entry.state() == null ? "" : BlockStateParser.serialize(entry.state()));
            blocks.add(block);
        }
        JsonObject root = new JsonObject();
        root.addProperty(VERSION_KEY, VERSION);
        root.addProperty(DATA_VERSION_KEY, SharedConstants.getCurrentVersion().dataVersion().version());
        root.add(BLOCKS_KEY, blocks);
        return DataFiles.writeJson(DataFiles.jsonFile(folder(), name), root);
    }

    private static List<Entry> box(int fromX, int toX, int fromY, int toY, int fromZ, int toZ, BlockState state) {
        List<Entry> entries = new ArrayList<>();
        for (int y = fromY; y <= toY; y++) {
            for (int z = fromZ; z <= toZ; z++) {
                for (int x = fromX; x <= toX; x++) {
                    entries.add(new Entry(x, y, z, state));
                }
            }
        }
        return entries;
    }

    private static List<Entry> tower(int radius, int height) {
        List<Entry> entries = box(-radius, radius, 0, height - 1, 0, radius * 2, null);
        entries.removeIf(entry -> Math.abs(entry.x()) < radius && entry.z() > 0 && entry.z() < radius * 2);
        return entries;
    }

    private static List<Entry> golem(BlockState body, boolean arms) {
        List<Entry> entries = box(0, 0, 0, 1, 0, 0, body);
        if (arms) {
            entries.add(new Entry(-1, 1, 0, body));
            entries.add(new Entry(1, 1, 0, body));
        }
        entries.add(new Entry(0, 2, 0, Blocks.CARVED_PUMPKIN.defaultBlockState()
            .setValue(CarvedPumpkinBlock.FACING, Direction.NORTH)));
        return entries;
    }

    private static List<Entry> portal() {
        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        int top = PORTAL_INSIDE_HEIGHT + 1;
        List<Entry> entries = box(0, PORTAL_INSIDE_WIDTH - 1, 0, 0, 0, 0, obsidian);
        entries.addAll(box(-1, -1, 1, PORTAL_INSIDE_HEIGHT, 0, 0, obsidian));
        entries.addAll(box(PORTAL_INSIDE_WIDTH, PORTAL_INSIDE_WIDTH, 1, PORTAL_INSIDE_HEIGHT, 0, 0, obsidian));
        entries.addAll(box(0, PORTAL_INSIDE_WIDTH - 1, top, top, 0, 0, obsidian));
        return entries;
    }
}
