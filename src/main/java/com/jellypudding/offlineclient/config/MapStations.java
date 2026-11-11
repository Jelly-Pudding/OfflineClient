package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.MapPicture;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

// The map printing stations and how far each one has got. A station belongs to one dimension of
// one server. It knows the north west corner of its map square at the height the picture lies
// and the blocks it works with and the file under way. Kept in offlineclient/map_stations.json
// and outlasting a disconnect and a restart.
public final class MapStations {

    // What the printer is busy with for the file under way.
    public enum Stage { BUILD, MAP, LOCK, STORE, CLEAR }

    // A block of the station with one job. The chests of materials are any number.
    public enum Role { TABLE, MAPS, FINISHED, DUMP, RESET, CHESTS }

    // A map id that stands for none.
    public static final int NO_MAP = -1;

    // Bounds on what a damaged or hand made file can make the client hold.
    private static final int MOST_STATIONS = 64;
    private static final int MOST_CHESTS = 256;
    private static final int LONGEST_NAME = 255;
    // Every cell of a picture written as bits takes this many letters at most. Four letters hold three bytes.
    private static final int CELL_LETTERS = ((MapPicture.MOST_CELLS + Byte.SIZE - 1) / Byte.SIZE + 2) / 3 * 4;

    // A chest of materials with every kind it was seen holding whilst it stayed marked.
    public record Chest(BlockPos pos, Set<Item> items) {

        public Chest {
            pos = pos.immutable();
            items = Set.copyOf(items);
        }

        public boolean holds(Item item) {
            return items.contains(item);
        }
    }

    public static final class Station {

        private final String world;
        private final BlockPos corner;
        private BlockPos table;
        private BlockPos maps;
        private BlockPos finished;
        private BlockPos dump;
        private BlockPos reset;
        private final List<Chest> chests = new ArrayList<>();
        private boolean marked;
        private boolean working;
        private String file;
        private Stage stage = Stage.BUILD;
        private boolean laid;
        private final BitSet right = new BitSet(MapPicture.MOST_CELLS);
        private int map = NO_MAP;
        private int locked = NO_MAP;
        private Set<Integer> mapsBefore = Set.of();

        private Station(String world, BlockPos corner) {
            this.world = world;
            this.corner = corner.immutable();
        }

        // The north west corner of the map square at the height the picture lies.
        public BlockPos corner() {
            return corner;
        }

        // Null for a role nothing was marked for. The chests have a list of their own.
        public BlockPos block(Role role) {
            return switch (role) {
                case TABLE -> table;
                case MAPS -> maps;
                case FINISHED -> finished;
                case DUMP -> dump;
                case RESET -> reset;
                case CHESTS -> null;
            };
        }

        public void mark(Role role, BlockPos pos) {
            BlockPos kept = pos == null ? null : pos.immutable();
            switch (role) {
                case TABLE -> table = kept;
                case MAPS -> maps = kept;
                case FINISHED -> finished = kept;
                case DUMP -> dump = kept;
                case RESET -> reset = kept;
                case CHESTS -> {
                }
            }
        }

        public List<Chest> chests() {
            return List.copyOf(chests);
        }

        // The chest marked at the spot. Null when none is.
        public Chest chestAt(BlockPos pos) {
            for (Chest chest : chests) {
                if (chest.pos().equals(pos)) {
                    return chest;
                }
            }
            return null;
        }

        // True when any marked chest was seen holding the item.
        public boolean anyChestHolds(Item item) {
            for (Chest chest : chests) {
                if (chest.holds(item)) {
                    return true;
                }
            }
            return false;
        }

        // Adds a chest or adds the kinds a marked one holds to those it was seen with. A chest
        // emptied of a kind stays known for it. True when something was learnt.
        public boolean seen(BlockPos pos, Set<Item> items) {
            Chest known = chestAt(pos);
            if (known != null && known.items().containsAll(items)) {
                return false;
            }
            if (known == null) {
                chests.add(new Chest(pos, items));
                return true;
            }
            Set<Item> kinds = new LinkedHashSet<>(known.items());
            kinds.addAll(items);
            chests.set(chests.indexOf(known), new Chest(pos, kinds));
            return true;
        }

        public boolean unmark(BlockPos pos) {
            return chests.removeIf(chest -> chest.pos().equals(pos));
        }

        // True once every block it needs has been marked and the marking was ended.
        public boolean marked() {
            return marked;
        }

        public void setMarked(boolean marked) {
            this.marked = marked;
        }

        // True for the station the printer was last switched on for. It carries on only there.
        public boolean working() {
            return working;
        }

        // The file under way. Null between files.
        public String file() {
            return file;
        }

        public Stage stage() {
            return stage;
        }

        // True once the picture of the file under way was laid on the square. A file that was
        // laid is never passed over for another because part of it may stand.
        public boolean laid() {
            return laid;
        }

        public void setLaid() {
            laid = true;
        }

        // The cells of the picture last seen right by row from the north and then by column.
        // A cell out of view keeps what was seen there.
        public BitSet right() {
            return right;
        }

        public int map() {
            return map;
        }

        public int locked() {
            return locked;
        }

        // The maps carried as the locked copy was taken from the cartography table. Empty
        // whilst no copy was taken.
        public Set<Integer> mapsBefore() {
            return mapsBefore;
        }

        // Starts a file at its first stage.
        public void begin(String fileName) {
            file = fileName;
            stage = Stage.BUILD;
            laid = false;
            right.clear();
            map = NO_MAP;
            locked = NO_MAP;
            mapsBefore = Set.of();
        }

        public void reach(Stage next) {
            stage = next;
        }

        public void setMap(int id) {
            map = id;
        }

        public void setLocked(int id) {
            locked = id;
            mapsBefore = Set.of();
        }

        public void setMapsBefore(Set<Integer> ids) {
            mapsBefore = Set.copyOf(ids);
        }

        // The file is done with and the next one starts from scratch.
        public void end() {
            begin(null);
        }
    }

    private static MapStations instance;

    private final Path path;
    private final List<Station> stations = new ArrayList<>();

    private MapStations(Path path) {
        this.path = path;
        DataFiles.readJson(path, MapStations::decode).ifPresent(stations::addAll);
    }

    private static synchronized MapStations get() {
        if (instance == null) {
            instance = new MapStations(DataFiles.path("map_stations.json"));
        }
        return instance;
    }

    // The station of the world you are in. Null when it has none.
    public static Station here() {
        String world = ServerInfo.worldKey();
        for (Station station : get().stations) {
            if (station.world.equals(world)) {
                return station;
            }
        }
        return null;
    }

    // A new station for the world you are in. It takes the place of the one there was and the
    // printer works for it.
    public static Station fresh(BlockPos corner) {
        String world = ServerInfo.worldKey();
        MapStations store = get();
        store.stations.removeIf(station -> station.world.equals(world));
        Station station = new Station(world, corner);
        store.stations.add(station);
        workHere();
        return station;
    }

    // The station of the world you are in becomes the one the printer works for.
    public static void workHere() {
        String world = ServerInfo.worldKey();
        for (Station station : get().stations) {
            station.working = station.world.equals(world);
        }
        save();
    }

    public static void save() {
        MapStations store = get();
        JsonArray root = new JsonArray();
        for (Station station : store.stations) {
            root.add(encode(station));
        }
        DataFiles.writeJson(store.path, root);
    }

    private static JsonObject encode(Station station) {
        JsonObject saved = new JsonObject();
        saved.addProperty("world", station.world);
        saved.addProperty("corner", BlockUtil.text(station.corner));
        for (Role role : Role.values()) {
            BlockPos pos = station.block(role);
            if (pos != null) {
                saved.addProperty(key(role), BlockUtil.text(pos));
            }
        }
        JsonArray chests = new JsonArray();
        for (Chest chest : station.chests) {
            JsonObject entry = new JsonObject();
            entry.addProperty("pos", BlockUtil.text(chest.pos()));
            JsonArray items = new JsonArray();
            chest.items().forEach(item -> items.add(BuiltInRegistries.ITEM.getKey(item).toString()));
            entry.add("items", items);
            chests.add(entry);
        }
        saved.add("chests", chests);
        saved.addProperty("marked", station.marked);
        saved.addProperty("working", station.working);
        if (station.file != null) {
            saved.addProperty("file", station.file);
        }
        saved.addProperty("stage", station.stage.name());
        saved.addProperty("laid", station.laid);
        saved.addProperty("right", Base64.getEncoder().encodeToString(station.right.toByteArray()));
        saved.addProperty("map", station.map);
        saved.addProperty("locked", station.locked);
        JsonArray before = new JsonArray();
        station.mapsBefore.forEach(before::add);
        saved.add("before", before);
        return saved;
    }

    private static String key(Role role) {
        return role.name().toLowerCase(Locale.ROOT);
    }

    // A station that cannot be read is left out and the others stay up to the bound.
    private static List<Station> decode(JsonElement root) {
        List<Station> decoded = DataFiles.entries(root, MapStations::station, "map stations");
        return decoded.size() > MOST_STATIONS ? decoded.subList(0, MOST_STATIONS) : decoded;
    }

    // Null for a station without its world or its corner.
    private static Station station(JsonElement element) {
        if (!(element instanceof JsonObject saved)) {
            return null;
        }
        String world = JsonFields.text(saved, "world");
        BlockPos corner = position(saved, "corner");
        if (world == null || corner == null) {
            return null;
        }
        Station station = new Station(world, corner);
        for (Role role : Role.values()) {
            station.mark(role, position(saved, key(role)));
        }
        if (saved.get("chests") instanceof JsonArray chests) {
            for (JsonElement chest : chests) {
                if (station.chests.size() >= MOST_CHESTS) {
                    break;
                }
                if (chest instanceof JsonObject entry && position(entry, "pos") instanceof BlockPos pos) {
                    station.seen(pos, items(entry));
                }
            }
        }
        station.marked = Boolean.TRUE.equals(JsonFields.flag(saved, "marked"));
        station.working = Boolean.TRUE.equals(JsonFields.flag(saved, "working"));
        String file = JsonFields.text(saved, "file");
        station.file = file != null && plainName(file) ? file : null;
        station.stage = Objects.requireNonNullElse(JsonFields.choice(saved, "stage", Stage.class), Stage.BUILD);
        station.laid = Boolean.TRUE.equals(JsonFields.flag(saved, "laid"));
        station.right.or(cells(JsonFields.text(saved, "right")));
        station.map = id(saved, "map");
        station.locked = id(saved, "locked");
        station.mapsBefore = ids(saved, "before");
        return station;
    }

    private static BlockPos position(JsonObject saved, String key) {
        String text = JsonFields.text(saved, key);
        return text == null ? null : BlockUtil.parse(text);
    }

    // A file of the map folder never names another folder.
    private static boolean plainName(String name) {
        return !name.isEmpty() && name.length() <= LONGEST_NAME && name.indexOf('/') < 0 && name.indexOf('\\') < 0
            && !name.equals("..");
    }

    private static int id(JsonObject saved, String key) {
        Integer id = JsonFields.whole(saved, key);
        return id == null || id < 0 ? NO_MAP : id;
    }

    // No more ids than the inventory has slots.
    private static Set<Integer> ids(JsonObject saved, String key) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (saved.get(key) instanceof JsonArray names) {
            for (JsonElement name : names) {
                if (ids.size() >= InventoryUtil.WHOLE_INVENTORY) {
                    break;
                }
                Integer id = JsonFields.whole(name);
                if (id != null && id >= 0) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    // The cells written as bytes. Text longer than every cell of a picture takes is no such list.
    private static BitSet cells(String text) {
        if (text == null || text.length() > CELL_LETTERS) {
            return new BitSet();
        }
        try {
            return BitSet.valueOf(Base64.getDecoder().decode(text)).get(0, MapPicture.MOST_CELLS);
        } catch (IllegalArgumentException e) {
            return new BitSet();
        }
    }

    // No chest can be known for more kinds than the game has items.
    private static Set<Item> items(JsonObject entry) {
        Set<Item> items = new LinkedHashSet<>();
        if (entry.get("items") instanceof JsonArray names) {
            int most = BuiltInRegistries.ITEM.size();
            int read = 0;
            for (JsonElement name : names) {
                if (read++ >= most) {
                    break;
                }
                Identifier id = name.isJsonPrimitive() ? Identifier.tryParse(name.getAsString()) : null;
                Optional<Item> item = id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
                item.ifPresent(items::add);
            }
        }
        return items;
    }
}
