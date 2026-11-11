package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

// Blocks marked for one purpose. Each mark is filed under the server and the dimension it was
// made in and every purpose keeps a file of its own under offlineclient.
public final class BlockMarks {

    private static BlockMarks containers;
    private static BlockMarks lootedVaults;

    private final Path file;
    private final Set<String> marks = new HashSet<>();

    private BlockMarks(String fileName) {
        file = DataFiles.path(fileName);
        DataFiles.readJson(file, root -> DataFiles.entries(root, JsonElement::getAsString, "marks in " + fileName))
            .ifPresent(marks::addAll);
    }

    // The containers ChestAura leaves alone.
    public static synchronized BlockMarks containers() {
        if (containers == null) {
            containers = new BlockMarks("container_marks.json");
        }
        return containers;
    }

    // The vaults TrialESP saw reward you.
    public static synchronized BlockMarks lootedVaults() {
        if (lootedVaults == null) {
            lootedVaults = new BlockMarks("looted_vaults.json");
        }
        return lootedVaults;
    }

    // A null position is never marked. The other half of a single chest is null.
    public boolean isMarked(BlockPos pos) {
        return pos != null && marks.contains(key(pos));
    }

    // The file is written only when the mark changes.
    public void set(BlockPos pos, boolean marked) {
        String key = key(pos);
        if (marked ? marks.add(key) : marks.remove(key)) {
            save();
        }
    }

    // Marks every block given or clears them all when any of them is marked already.
    // True when they end up marked. The nulls are skipped.
    public boolean toggle(BlockPos... blocks) {
        boolean marking = true;
        for (BlockPos pos : blocks) {
            if (isMarked(pos)) {
                marking = false;
            }
        }
        for (BlockPos pos : blocks) {
            if (pos == null) {
                continue;
            }
            if (marking) {
                marks.add(key(pos));
            } else {
                marks.remove(key(pos));
            }
        }
        save();
        return marking;
    }

    private static String key(BlockPos pos) {
        return ServerInfo.worldKey() + " " + BlockUtil.text(pos);
    }

    private void save() {
        JsonArray root = new JsonArray();
        marks.stream().sorted().forEach(root::add);
        DataFiles.writeJson(file, root);
    }
}
