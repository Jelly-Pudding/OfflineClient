package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.util.ServerInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// The areas NoReturn keeps you out of. Each is filed under the server and the dimension it
// was closed in and saved to offlineclient/closed_areas.json.
public final class ClosedAreas {

    // A square of chunks round a centre chunk. A radius of nought follows your render distance.
    public record Area(String server, String dimension, int chunkX, int chunkZ, int radius) {
    }

    private static ClosedAreas instance;

    private final Path file;
    private final List<Area> areas = new ArrayList<>();

    private ClosedAreas(Path file) {
        this.file = file;
        DataFiles.readJson(file, ClosedAreas::decode).ifPresent(areas::addAll);
    }

    public static synchronized ClosedAreas get() {
        if (instance == null) {
            instance = new ClosedAreas(DataFiles.path("closed_areas.json"));
        }
        return instance;
    }

    // The areas of the server you play on in the order they were closed.
    public List<Area> onServer() {
        String server = ServerInfo.key();
        return areas.stream().filter(area -> ServerInfo.matches(area.server(), server)).toList();
    }

    // The areas of the dimension you are in.
    public List<Area> here() {
        String dimension = ServerInfo.dimension();
        return onServer().stream().filter(area -> area.dimension().equals(dimension)).toList();
    }

    // Closes the chunks round a centre in the dimension you are in. False when an area with
    // that centre is closed already.
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

    // Opens every area of this server and says how many there were.
    public int openAll() {
        List<Area> gone = onServer();
        areas.removeAll(gone);
        if (!gone.isEmpty()) {
            save();
        }
        return gone.size();
    }

    private static List<Area> decode(JsonElement root) {
        List<Area> decoded = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            JsonObject o = element.getAsJsonObject();
            decoded.add(new Area(o.get("server").getAsString(), o.get("dimension").getAsString(),
                o.get("x").getAsInt(), o.get("z").getAsInt(), o.get("radius").getAsInt()));
        }
        return decoded;
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
