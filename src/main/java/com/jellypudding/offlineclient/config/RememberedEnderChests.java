package com.jellypudding.offlineclient.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;

// What the ender chest of each name you play under held the last time it was open on the
// server you play on. The file sits beside the remembered containers.
public final class RememberedEnderChests extends ServerLedger<String, List<ItemStack>> {

    public static final int SLOTS = 27;

    private static final String FILE = "enderchests.json";
    private static final String WHAT = "remembered ender chests";
    // Far more names than one player joins a server under. It only bounds the memory and the file.
    private static final int MAX_NAMES = 256;
    // No server hands out a longer name. A longer one in a file is a broken file.
    private static final int MOST_LETTERS = 64;

    public RememberedEnderChests() {
        super(FILE, MAX_NAMES, WHAT);
    }

    // The slots in order with the empty ones in place. Empty for a name never seen and whilst
    // the file is still being read.
    public List<ItemStack> of(String name) {
        List<ItemStack> items = get(key(name));
        return items == null ? List.of() : items;
    }

    // What the open ender chest holds this tick. Nothing is written whilst it holds the same.
    public void update(String name, List<ItemStack> items) {
        String key = key(name);
        List<ItemStack> old = get(key);
        if (old == null || !ItemUtil.sameStacks(old, items)) {
            put(key, List.copyOf(items));
        }
    }

    // A server tells two names apart by their letters and never by their case.
    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // One broken chest is left out rather than the whole file.
    @Override
    protected void parse(JsonElement root, Map<String, List<ItemStack>> into, RegistryOps<JsonElement> ops) {
        into.putAll(DataFiles.members(root, (name, items) -> name.length() > MOST_LETTERS ? null
            : SlotStacks.decode(items.getAsJsonArray(), SLOTS, ops), WHAT));
    }

    @Override
    protected JsonElement encode(Map<String, List<ItemStack>> chests, RegistryOps<JsonElement> ops) {
        JsonObject root = new JsonObject();
        chests.forEach((name, items) -> root.add(name, SlotStacks.encode(items, ops)));
        return root;
    }
}
