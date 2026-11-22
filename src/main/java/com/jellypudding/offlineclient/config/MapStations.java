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

public final class MapStations {

    public enum Stage { BUILD, MAP, LOCK, STORE, CLEAR }

    public enum Role { TABLE, MAPS, FINISHED, DUMP, RESET, CHESTS }

    public static final int NO_MAP = -1;

    // Bounds on what a damaged or hand made file can make the client hold.
    private static final int MOST_STATIONS = 64;
    private static final int MOST_CHESTS = 256;
    private static final int LONGEST_NAME = 255;
    // Every cell of a picture written as bits takes this many letters at most. Four letters hold three bytes.
    private static final int CELL_LETTERS = ((MapPicture.MOST_CELLS + Byte.SIZE - 1) / Byte.SIZE + 2) / 3 * 4;

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

        public BlockPos corner() {
            return corner;
        }

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

        public Chest chestAt(BlockPos pos) {
            for (Chest chest : chests) {
                if (chest.pos().equals(pos)) {
                    return chest;
                }
            }
            return null;
        }

        public boolean anyChestHolds(Item item) {
            for (Chest chest : chests) {
                if (chest.holds(item)) {
                    return true;
                }
            }
            return false;
        }

        // A chest emptied of a kind stays known for it.
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

        public boolean marked() {
            return marked;
        }

        public void setMarked(boolean marked) {
            this.marked = marked;
        }

        public boolean working() {
            return working;
        }

        public String file() {
            return file;
        }

        public Stage stage() {
            return stage;
        }

        // A file that was laid is never passed over for another because part of it may stand.
        public boolean laid() {
            return laid;
        }

        public void setLaid() {
            laid = true;
        }

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

        public Set<Integer> mapsBefore() {
            return mapsBefore;
        }

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

    public static Station here() {
        String world = ServerInfo.worldKey();
        for (Station station : get().stations) {
            if (station.world.equals(world)) {
                return station;
            }
        }
        return null;
    }

    public static Station fresh(BlockPos corner) {
        String world = ServerInfo.worldKey();
        MapStations store = get();
        store.stations.removeIf(station -> station.world.equals(world));
        Station station = new Station(world, corner);
        store.stations.add(station);
        workHere();
        return station;
    }

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

    private static List<Station> decode(JsonElement root) {
        List<Station> decoded = DataFiles.entries(root, MapStations::station, "map stations");
        return decoded.size() > MOST_STATIONS ? decoded.subList(0, MOST_STATIONS) : decoded;
    }

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
