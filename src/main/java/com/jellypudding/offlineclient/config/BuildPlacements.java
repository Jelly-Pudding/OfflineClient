package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Kept in offlineclient/builds.json. They outlast a relog or a death.
public final class BuildPlacements {

    public record Placement(String name, String file, BlockPos origin, Rotation rotation, Mirror mirror,
                            boolean hidden, String dimension, String server) {

        public BuildFrame frame() {
            return new BuildFrame(origin, rotation, mirror);
        }

        public Placement with(BuildFrame frame) {
            return new Placement(name, file, frame.origin(), frame.rotation(), frame.mirror(), hidden, dimension,
                server);
        }

        public Placement withHidden(boolean hidden) {
            return new Placement(name, file, origin, rotation, mirror, hidden, dimension, server);
        }
    }

    private static BuildPlacements instance;

    private final Path file;
    private final List<Placement> placements = new ArrayList<>();
    // Goes up with every change. A reader that kept the last count knows when to look again.
    private int version;

    private BuildPlacements(Path file) {
        this.file = file;
        DataFiles.readJson(file, root -> DataFiles.entries(root, BuildPlacements::placement, "build placements"))
            .ifPresent(placements::addAll);
    }

    public static synchronized BuildPlacements get() {
        if (instance == null) {
            instance = new BuildPlacements(DataFiles.path("builds.json"));
        }
        return instance;
    }

    public int version() {
        return version;
    }

    public List<Placement> here() {
        String server = ServerInfo.key();
        String dimension = ServerInfo.dimension();
        List<Placement> matching = new ArrayList<>();
        for (Placement placement : placements) {
            if (isIn(placement, dimension, server)) {
                matching.add(placement);
            }
        }
        return matching;
    }

    public Placement find(String name) {
        for (Placement placement : here()) {
            if (placement.name().equalsIgnoreCase(name)) {
                return placement;
            }
        }
        return null;
    }

    // Named after the file with a number behind it when the plain name is taken.
    public Placement add(String fileName, BuildFrame frame) {
        String stem = DataFiles.safeName(BuildFiles.stem(fileName));
        String name = stem;
        for (int number = 2; find(name) != null; number++) {
            name = stem + number;
        }
        Placement placement = new Placement(name, fileName, frame.origin(), frame.rotation(), frame.mirror(), false,
            ServerInfo.dimension(), ServerInfo.key());
        placements.add(placement);
        changed();
        return placement;
    }

    public void update(Placement placement) {
        placements.replaceAll(saved -> same(saved, placement) ? placement : saved);
        changed();
    }

    public void remove(Placement placement) {
        placements.removeIf(saved -> same(saved, placement));
        changed();
    }

    private static boolean same(Placement saved, Placement other) {
        return saved.name().equalsIgnoreCase(other.name()) && isIn(saved, other.dimension(), other.server());
    }

    private static boolean isIn(Placement placement, String dimension, String server) {
        return placement.dimension().equals(dimension) && ServerInfo.matches(placement.server(), server);
    }

    private void changed() {
        version++;
        JsonArray root = new JsonArray();
        for (Placement placement : placements) {
            JsonObject saved = new JsonObject();
            saved.addProperty("name", placement.name());
            saved.addProperty("file", placement.file());
            saved.addProperty("x", placement.origin().getX());
            saved.addProperty("y", placement.origin().getY());
            saved.addProperty("z", placement.origin().getZ());
            saved.addProperty("rotation", placement.rotation().name());
            saved.addProperty("mirror", placement.mirror().name());
            saved.addProperty("hidden", placement.hidden());
            saved.addProperty("dimension", placement.dimension());
            saved.addProperty("server", placement.server());
            root.add(saved);
        }
        DataFiles.writeJson(file, root);
    }

    private static Placement placement(JsonElement element) {
        JsonObject saved = element.getAsJsonObject();
        return new Placement(saved.get("name").getAsString(), saved.get("file").getAsString(),
            new BlockPos(saved.get("x").getAsInt(), saved.get("y").getAsInt(), saved.get("z").getAsInt()),
            Rotation.valueOf(saved.get("rotation").getAsString()),
            Mirror.valueOf(saved.get("mirror").getAsString()), saved.get("hidden").getAsBoolean(),
            saved.get("dimension").getAsString(), saved.get("server").getAsString());
    }
}
