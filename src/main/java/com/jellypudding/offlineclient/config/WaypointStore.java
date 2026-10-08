package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        // The middle of the block it marks.
        public Vec3 centre() {
            return Vec3.atCenterOf(pos());
        }
    }

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    // Eight overworld blocks to one nether block.
    private static final int NETHER_SCALE = 8;

    private static WaypointStore instance;

    private final Path file;
    private final List<Waypoint> waypoints = new ArrayList<>();

    private WaypointStore(Path file) {
        this.file = file;
        load();
    }

    public static synchronized WaypointStore get() {
        if (instance == null) {
            instance = new WaypointStore(DataFiles.path("waypoints.json"));
        }
        return instance;
    }

    // Only the waypoints marked in the world the player is standing in.
    public List<Waypoint> here() {
        return in(ServerInfo.dimension());
    }

    // The waypoints of one dimension of this server.
    public List<Waypoint> in(String dimension) {
        String server = ServerInfo.key();
        List<Waypoint> matching = new ArrayList<>();
        for (Waypoint waypoint : waypoints) {
            if (waypoint.dimension().equals(dimension) && ServerInfo.matches(waypoint.server(), server)) {
                matching.add(waypoint);
            }
        }
        return matching;
    }

    // Every dimension of this server that holds a waypoint.
    public List<String> dimensions() {
        String server = ServerInfo.key();
        Set<String> found = new LinkedHashSet<>();
        for (Waypoint waypoint : waypoints) {
            if (ServerInfo.matches(waypoint.server(), server)) {
                found.add(waypoint.dimension());
            }
        }
        return new ArrayList<>(found);
    }

    // The block a coordinate lands in on the other side of a nether portal. It shrinks going in
    // and grows coming out. The game scales the exact position and only then takes the block.
    public static int acrossPortal(double coordinate, boolean intoNether) {
        return Mth.floor(intoNether ? coordinate / NETHER_SCALE : coordinate * NETHER_SCALE);
    }

    // The dimension a nether portal leads to from this one. Null in the End and anywhere else
    // a nether portal does not work.
    public static String otherSide(String dimension) {
        return dimension.equals(OVERWORLD) ? NETHER : dimension.equals(NETHER) ? OVERWORLD : null;
    }

    // The block in the other dimension that matches this exact spot at the same height. Null
    // where there is no other side.
    public static BlockPos acrossPortal(Vec3 position, String dimension) {
        String other = otherSide(dimension);
        if (other == null) {
            return null;
        }
        boolean intoNether = other.equals(NETHER);
        return new BlockPos(acrossPortal(position.x(), intoNether), Mth.floor(position.y()),
            acrossPortal(position.z(), intoNether));
    }

    // Waypoints from the other side of a nether portal with their coordinates scaled
    // to this side. Empty anywhere but the overworld and the nether.
    public List<Waypoint> mirrored() {
        String dimension = ServerInfo.dimension();
        String server = ServerInfo.key();
        String other = otherSide(dimension);
        List<Waypoint> matching = new ArrayList<>();
        if (other == null) {
            return matching;
        }
        for (Waypoint waypoint : waypoints) {
            if (!waypoint.dimension().equals(other) || !ServerInfo.matches(waypoint.server(), server)) {
                continue;
            }
            BlockPos here = acrossPortal(Vec3.atLowerCornerOf(waypoint.pos()), other);
            matching.add(new Waypoint(waypoint.name(), here.getX(), here.getY(), here.getZ(), dimension, server,
                waypoint.hue(), waypoint.hidden()));
        }
        return matching;
    }

    // An existing waypoint of the same name in the new one's world is replaced.
    public void add(Waypoint waypoint) {
        waypoints.removeIf(existing -> named(existing, waypoint.name(), waypoint.dimension(), waypoint.server()));
        waypoints.add(waypoint);
        save();
    }

    // Saves a waypoint in that dimension of this server. One of the same name there moves and
    // keeps its colour and whether it is hidden. The hue is for a new name.
    public void mark(String name, BlockPos pos, String dimension, int hue) {
        String server = ServerInfo.key();
        Waypoint old = lookup(name, dimension, server);
        add(new Waypoint(name, pos.getX(), pos.getY(), pos.getZ(), dimension, server,
            old == null ? hue : old.hue(), old != null && old.hidden()));
    }

    // The same in the dimension you are in.
    public void mark(String name, BlockPos pos, int hue) {
        mark(name, pos, ServerInfo.dimension(), hue);
    }

    // Saves a new waypoint in that dimension of this server named after the stem with the first
    // number no waypoint there uses such as stash3. Returns the name it took.
    public String markFresh(String stem, BlockPos pos, String dimension, int hue) {
        String server = ServerInfo.key();
        int number = 1;
        while (lookup(stem + number, dimension, server) != null) {
            number++;
        }
        String name = stem + number;
        mark(name, pos, dimension, hue);
        return name;
    }

    // Null when no waypoint of that name is in this world.
    public Waypoint find(String name) {
        return find(name, ServerInfo.dimension());
    }

    // Null when that dimension of this server holds no waypoint of that name.
    public Waypoint find(String name, String dimension) {
        return lookup(name, dimension, ServerInfo.key());
    }

    // Null when that dimension of that server holds no waypoint of that name.
    private Waypoint lookup(String name, String dimension, String server) {
        for (Waypoint waypoint : waypoints) {
            if (named(waypoint, name, dimension, server)) {
                return waypoint;
            }
        }
        return null;
    }

    public boolean remove(String name) {
        return remove(name, ServerInfo.dimension());
    }

    public boolean remove(String name, String dimension) {
        String server = ServerInfo.key();
        boolean removed = waypoints.removeIf(waypoint -> named(waypoint, name, dimension, server));
        if (removed) {
            save();
        }
        return removed;
    }

    // The name matches and the waypoint belongs to that dimension of that server. A base in
    // the Nether never stands in for one in the overworld.
    private static boolean named(Waypoint waypoint, String name, String dimension, String server) {
        return waypoint.name().equalsIgnoreCase(name)
            && waypoint.dimension().equals(dimension)
            && ServerInfo.matches(waypoint.server(), server);
    }

    public void clear() {
        waypoints.clear();
        save();
    }

    private void load() {
        DataFiles.readJson(file, WaypointStore::decode).ifPresent(waypoints::addAll);
    }

    private static List<Waypoint> decode(JsonElement root) {
        List<Waypoint> decoded = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            JsonObject o = element.getAsJsonObject();
            decoded.add(new Waypoint(
                o.get("name").getAsString(),
                o.get("x").getAsInt(),
                o.get("y").getAsInt(),
                o.get("z").getAsInt(),
                o.get("dimension").getAsString(),
                o.get("server").getAsString(),
                o.has("hue") ? o.get("hue").getAsInt() : Waypoint.AUTO_HUE,
                o.has("hidden") && o.get("hidden").getAsBoolean()));
        }
        return decoded;
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
        DataFiles.writeJson(file, root);
    }
}
