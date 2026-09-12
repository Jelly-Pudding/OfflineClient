package com.jellypudding.offlineclient.config;

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

// Named coordinates saved to offlineclient/waypoints.json.
// Each one remembers the server and the dimension it was marked in.
public final class WaypointStore {

    // A hue below zero means the colour comes from the name.
    public record Waypoint(String name, int x, int y, int z, String dimension, String server,
                           int hue, boolean hidden) {

        public static final int AUTO_HUE = -1;

        public Waypoint(String name, int x, int y, int z, String dimension, String server, int hue) {
            this(name, x, y, z, dimension, server, hue, false);
        }

        public Waypoint withHue(int hue) {
            return new Waypoint(name, x, y, z, dimension, server, hue, hidden);
        }

        public Waypoint withHidden(boolean hidden) {
            return new Waypoint(name, x, y, z, dimension, server, hue, hidden);
        }
    }

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    // Eight overworld blocks to one nether block.
    private static final int NETHER_SCALE = 8;

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

    // Waypoints from the other side of a nether portal with their coordinates scaled
    // to this side. Empty anywhere but the overworld and the nether.
    public List<Waypoint> mirrored() {
        String dimension = currentDimension();
        String server = currentServer();
        String other = dimension.equals(OVERWORLD) ? NETHER : dimension.equals(NETHER) ? OVERWORLD : null;
        List<Waypoint> matching = new ArrayList<>();
        if (other == null) {
            return matching;
        }
        boolean shrink = other.equals(OVERWORLD);
        for (Waypoint waypoint : waypoints) {
            if (!waypoint.dimension().equals(other) || !waypoint.server().equals(server)) {
                continue;
            }
            int x = shrink ? Math.floorDiv(waypoint.x(), NETHER_SCALE) : waypoint.x() * NETHER_SCALE;
            int z = shrink ? Math.floorDiv(waypoint.z(), NETHER_SCALE) : waypoint.z() * NETHER_SCALE;
            matching.add(new Waypoint(waypoint.name(), x, waypoint.y(), z, dimension, server,
                waypoint.hue(), waypoint.hidden()));
        }
        return matching;
    }

    // An existing waypoint of the same name in this world is replaced.
    public void add(Waypoint waypoint) {
        waypoints.removeIf(existing -> sameSpot(existing, waypoint.name()));
        waypoints.add(waypoint);
        save();
    }

    // Null when no waypoint of that name is in this world.
    public Waypoint find(String name) {
        for (Waypoint waypoint : waypoints) {
            if (sameSpot(waypoint, name)) {
                return waypoint;
            }
        }
        return null;
    }

    public boolean remove(String name) {
        boolean removed = waypoints.removeIf(waypoint -> sameSpot(waypoint, name));
        if (removed) {
            save();
        }
        return removed;
    }

    // The name matches and the waypoint belongs to the world the player is standing in.
    // A base in the Nether never stands in for one in the overworld.
    private static boolean sameSpot(Waypoint waypoint, String name) {
        return waypoint.name().equalsIgnoreCase(name)
            && waypoint.dimension().equals(currentDimension())
            && waypoint.server().equals(currentServer());
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
                    o.get("server").getAsString(),
                    o.has("hue") ? o.get("hue").getAsInt() : Waypoint.AUTO_HUE,
                    o.has("hidden") && o.get("hidden").getAsBoolean()));
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
            if (waypoint.hue() >= 0) {
                o.addProperty("hue", waypoint.hue());
            }
            if (waypoint.hidden()) {
                o.addProperty("hidden", true);
            }
            root.add(o);
        }
        ConfigManager.write(file, root);
    }
}
