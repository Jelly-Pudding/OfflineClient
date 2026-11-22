package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

public final class RememberedContainers
    extends ServerLedger<RememberedContainers.Spot, RememberedContainers.Container> {

    public record Spot(String dimension, BlockPos pos) {

        public static Spot here(BlockPos pos) {
            return new Spot(ServerInfo.dimension(), pos.immutable());
        }
    }

    // The stacks keep their slots with the empty ones in place.
    public record Container(Spot spot, List<ItemStack> items, long seen) {
    }

    private static final String FILE = "containers.json";
    private static final String WHAT = "remembered containers";
    // Each save reads and writes the whole file and the least recently used make room for new ones.
    private static final int MAX_CONTAINERS = 2048;
    // No container menu holds more slots. A larger count in a file is a broken file.
    private static final int MOST_SLOTS = 256;

    public RememberedContainers() {
        super(FILE, MAX_CONTAINERS, WHAT);
    }

    public void opened(Spot spot, List<ItemStack> items) {
        put(spot, new Container(spot, List.copyOf(items), System.currentTimeMillis()));
    }

    // Nothing is written whilst it holds the same.
    public void update(Spot spot, List<ItemStack> items) {
        Container old = get(spot);
        if (old == null || !ItemUtil.sameStacks(old.items(), items)) {
            opened(spot, items);
        }
    }

    // Safe to keep whilst the store changes.
    public List<Container> here() {
        String dimension = ServerInfo.dimension();
        return values().stream().filter(container -> container.spot().dimension().equals(dimension)).toList();
    }

    @Override
    protected void parse(JsonElement root, Map<Spot, Container> into, RegistryOps<JsonElement> ops) {
        DataFiles.entries(root, element -> fromJson(element.getAsJsonObject(), ops), WHAT)
            .forEach(container -> into.put(container.spot(), container));
    }

    @Override
    protected JsonElement encode(Map<Spot, Container> containers, RegistryOps<JsonElement> ops) {
        JsonArray root = new JsonArray();
        for (Container container : containers.values()) {
            root.add(toJson(container, ops));
        }
        return root;
    }

    private static Container fromJson(JsonObject object, RegistryOps<JsonElement> ops) {
        BlockPos pos = BlockUtil.parse(object.get("pos").getAsString());
        int size = object.get("size").getAsInt();
        if (pos == null || size < 0 || size > MOST_SLOTS) {
            return null;
        }
        List<ItemStack> items = SlotStacks.decode(object.getAsJsonArray("items"), size, ops);
        Spot spot = new Spot(object.get("dimension").getAsString(), pos);
        return new Container(spot, items, object.get("seen").getAsLong());
    }

    private static JsonObject toJson(Container container, RegistryOps<JsonElement> ops) {
        JsonObject object = new JsonObject();
        object.addProperty("dimension", container.spot().dimension());
        object.addProperty("pos", BlockUtil.text(container.spot().pos()));
        object.addProperty("seen", container.seen());
        object.addProperty("size", container.items().size());
        object.add("items", SlotStacks.encode(container.items(), ops));
        return object;
    }
}
