package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.util.ServerInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ClosedAreas {

    // A radius of nought follows your render distance.
    public record Area(String server, String dimension, int chunkX, int chunkZ, int radius) {
    }

    private static ClosedAreas instance;

    private final Path file;
    private final List<Area> areas = new ArrayList<>();

    private ClosedAreas(Path file) {
        this.file = file;
        DataFiles.readJson(file, root -> DataFiles.entries(root, ClosedAreas::area, "closed areas"))
            .ifPresent(areas::addAll);
    }

    public static synchronized ClosedAreas get() {
        if (instance == null) {
            instance = new ClosedAreas(DataFiles.path("closed_areas.json"));
        }
        return instance;
    }

    public List<Area> onServer() {
        String server = ServerInfo.key();
        return areas.stream().filter(area -> ServerInfo.matches(area.server(), server)).toList();
    }

    public List<Area> here() {
        String dimension = ServerInfo.dimension();
        return onServer().stream().filter(area -> area.dimension().equals(dimension)).toList();
    }

    public boolean close(int chunkX, int chunkZ, int radius) {
        for (Area area : here()) {
            if (area.chunkX() == chunkX && area.chunkZ() == chunkZ) {
                return false;
            }
        }
        areas.add(new Area(ServerInfo.key(), ServerInfo.dimension(), chunkX, chunkZ, radius));
        save();
        return true;
    }

    public void open(Area area) {
        if (areas.remove(area)) {
            save();
        }
    }

    public int openAll() {
        List<Area> gone = onServer();
        areas.removeAll(gone);
        if (!gone.isEmpty()) {
            save();
        }
        return gone.size();
    }

    private static Area area(JsonElement element) {
        JsonObject o = element.getAsJsonObject();
        return new Area(o.get("server").getAsString(), o.get("dimension").getAsString(),
            o.get("x").getAsInt(), o.get("z").getAsInt(), o.get("radius").getAsInt());
    }

    private void save() {
        JsonArray root = new JsonArray();
        for (Area area : areas) {
            JsonObject o = new JsonObject();
            o.addProperty("server", area.server());
            o.addProperty("dimension", area.dimension());
            o.addProperty("x", area.chunkX());
            o.addProperty("z", area.chunkZ());
            o.addProperty("radius", area.radius());
            root.add(o);
        }
        DataFiles.writeJson(file, root);
    }
}
