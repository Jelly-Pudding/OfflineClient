package com.jellypudding.offlineclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Named coordinates saved to offlineclient/waypoints.json. Each one
 * remembers the server and the dimension it was marked in.
 */
public final class WaypointStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record Waypoint(String name, int x, int y, int z, String dimension, String server) {
    }

    private static WaypointStore instance;

    private final Path file;
    private final List<Waypoint> waypoints = new ArrayList<>();

    private WaypointStore(Path folder) {
        this.file = folder.resolve("waypoints.json");
        load();
    }

    public static synchronized WaypointStore get() {
        if (instance == null) {
            Path folder = OfflineClient.MC.gameDirectory.toPath().resolve("offlineclient");
            try {
                Files.createDirectories(folder);
            } catch (IOException e) {
                OfflineClient.LOG.error("Failed to create the waypoint folder", e);
            }
            instance = new WaypointStore(folder);
        }
        return instance;
    }

    // Every saved waypoint on every server.
    public List<Waypoint> all() {
        return waypoints;
    }

    // Only the waypoints marked in the world the player is standing in.
    public List<Waypoint> here() {
        String dimension = currentDimension();
        String server = currentServer();
        List<Waypoint> matching = new ArrayList<>();
        for (Waypoint waypoint : waypoints) {
            if (waypoint.dimension().equals(dimension) && waypoint.server().equals(server)) {
                matching.add(waypoint);
            }
        }
        return matching;
    }

    public Waypoint find(String name) {
        for (Waypoint waypoint : waypoints) {
            if (waypoint.name().equalsIgnoreCase(name)) {
                return waypoint;
            }
        }
        return null;
    }

    // An existing waypoint with the same name is replaced.
    public void add(Waypoint waypoint) {
        waypoints.removeIf(existing -> existing.name().equalsIgnoreCase(waypoint.name()));
        waypoints.add(waypoint);
        save();
    }

    public boolean remove(String name) {
        boolean removed = waypoints.removeIf(waypoint -> waypoint.name().equalsIgnoreCase(name));
        if (removed) {
            save();
        }
        return removed;
    }

    public void clear() {
        waypoints.clear();
        save();
    }

    // Empty string when no world is loaded.
    public static String currentDimension() {
        return OfflineClient.MC.level == null
            ? "" : OfflineClient.MC.level.dimension().identifier().toString();
    }

    // Address of the server the player is on. Single player worlds share one key.
    public static String currentServer() {
        ServerData server = OfflineClient.MC.getCurrentServer();
        return server == null ? "singleplayer" : server.ip.toLowerCase(Locale.ROOT);
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file));
            for (JsonElement element : root.getAsJsonArray()) {
                JsonObject o = element.getAsJsonObject();
                waypoints.add(new Waypoint(
                    o.get("name").getAsString(),
                    o.get("x").getAsInt(),
                    o.get("y").getAsInt(),
                    o.get("z").getAsInt(),
                    o.get("dimension").getAsString(),
                    o.get("server").getAsString()));
            }
        } catch (Exception e) {
            OfflineClient.LOG.error("Failed to read waypoints", e);
        }
    }

    private void save() {
        JsonArray root = new JsonArray();
        for (Waypoint waypoint : waypoints) {
            JsonObject o = new JsonObject();
            o.addProperty("name", waypoint.name());
            o.addProperty("x", waypoint.x());
            o.addProperty("y", waypoint.y());
            o.addProperty("z", waypoint.z());
            o.addProperty("dimension", waypoint.dimension());
            o.addProperty("server", waypoint.server());
            root.add(o);
        }
        try {
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            OfflineClient.LOG.error("Failed to save waypoints", e);
        }
    }
}
