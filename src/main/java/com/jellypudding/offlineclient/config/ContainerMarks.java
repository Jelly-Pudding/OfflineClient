package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Containers ChestAura leaves alone. Each mark is filed under the server and the
// dimension it was made in.
public final class ContainerMarks {

    private static ContainerMarks instance;

    private final Path file;
    private final Set<String> marks = new HashSet<>();

    private ContainerMarks(Path file) {
        this.file = file;
        DataFiles.readJson(file, ContainerMarks::decode).ifPresent(marks::addAll);
    }

    public static synchronized ContainerMarks get() {
        if (instance == null) {
            instance = new ContainerMarks(DataFiles.path("container_marks.json"));
        }
        return instance;
    }

    // A null position is never marked. The other half of a single chest is null.
    public boolean isMarked(BlockPos pos) {
        return pos != null && marks.contains(key(pos));
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
        return ServerInfo.key() + " " + WaypointStore.currentDimension() + " " + BlockUtil.text(pos);
    }

    private static List<String> decode(JsonElement root) {
        List<String> decoded = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            decoded.add(element.getAsString());
        }
        return decoded;
    }

    private void save() {
        JsonArray root = new JsonArray();
        marks.stream().sorted().forEach(root::add);
        DataFiles.writeJson(file, root);
    }
}
