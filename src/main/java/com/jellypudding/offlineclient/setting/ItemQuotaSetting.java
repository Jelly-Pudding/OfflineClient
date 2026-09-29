package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Items picked from the registry each with how many of them are wanted. A count belongs to its
// item and never to a place in a list. A new pick starts at one full stack.
public final class ItemQuotaSetting extends Setting<Map<Identifier, Integer>> implements CountedPickList<Item> {

    // Items with a count. Read from other threads and replaced whole.
    private volatile Map<Item, Integer> resolved = Map.of();

    public ItemQuotaSetting(String name, String description, Map<Item, Integer> defaults) {
        super(name, description, toIds(defaults));
        this.value = new LinkedHashMap<>(defaultValue);
        rebuild();
    }

    private static Map<Identifier, Integer> toIds(Map<Item, Integer> counts) {
        Map<Identifier, Integer> ids = new LinkedHashMap<>();
        counts.forEach((item, count) -> ids.put(BuiltInRegistries.ITEM.getKey(item), count));
        return Collections.unmodifiableMap(ids);
    }

    // Minus one for an item that was never picked.
    public int countOf(Item item) {
        return resolved.getOrDefault(item, -1);
    }

    // Air stands for nothing and is never offered.
    @Override
    public Collection<Item> options() {
        List<Item> all = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR) {
                all.add(item);
            }
        }
        return all;
    }

    @Override
    public List<Item> chosen() {
        return new ArrayList<>(resolved.keySet());
    }

    @Override
    public boolean isChosen(Item entry) {
        return value.containsKey(BuiltInRegistries.ITEM.getKey(entry));
    }

    @Override
    public void add(Item entry) {
        if (value.putIfAbsent(BuiltInRegistries.ITEM.getKey(entry), entry.getDefaultMaxStackSize()) == null) {
            rebuild();
        }
    }

    @Override
    public void addAll(Collection<? extends Item> entries) {
        for (Item entry : entries) {
            value.putIfAbsent(BuiltInRegistries.ITEM.getKey(entry), entry.getDefaultMaxStackSize());
        }
        rebuild();
    }

    @Override
    public void remove(Item entry) {
        if (value.remove(BuiltInRegistries.ITEM.getKey(entry)) != null) {
            rebuild();
        }
    }

    @Override
    public void clear() {
        value.clear();
        rebuild();
    }

    @Override
    public int size() {
        return value.size();
    }

    @Override
    public int count(Item entry) {
        return Math.max(0, countOf(entry));
    }

    @Override
    public void setCount(Item entry, int count) {
        Identifier id = BuiltInRegistries.ITEM.getKey(entry);
        if (value.containsKey(id)) {
            value.put(id, Math.max(0, count));
            rebuild();
        }
    }

    @Override
    public String displayName(Item entry) {
        return entry.getName(entry.getDefaultInstance()).getString();
    }

    @Override
    public String idOf(Item entry) {
        return BuiltInRegistries.ITEM.getKey(entry).toString();
    }

    @Override
    public ItemStack icon(Item entry) {
        return entry.getDefaultInstance();
    }

    @Override
    public Map<Identifier, Integer> getValue() {
        return Collections.unmodifiableMap(value);
    }

    @Override
    public void setValue(Map<Identifier, Integer> value) {
        this.value = new LinkedHashMap<>(value);
        rebuild();
    }

    @Override
    public void reset() {
        value = new LinkedHashMap<>(defaultValue);
        rebuild();
    }

    @Override
    public String getValueString() {
        return size() + " chosen";
    }

    // An id the game does not know keeps its count in the config and is left out here. The item
    // registry hands back air for such an id.
    private void rebuild() {
        Map<Item, Integer> next = new LinkedHashMap<>();
        value.forEach((id, count) -> {
            if (BuiltInRegistries.ITEM.containsKey(id)) {
                next.put(BuiltInRegistries.ITEM.getValue(id), count);
            }
        });
        resolved = Collections.unmodifiableMap(next);
    }

    @Override
    public JsonElement toJson() {
        JsonObject object = new JsonObject();
        value.forEach((id, count) -> object.addProperty(id.toString(), count));
        return object;
    }

    @Override
    public void fromJson(JsonElement json) {
        if (!json.isJsonObject()) {
            return;
        }
        Map<Identifier, Integer> next = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
            Identifier id = Identifier.tryParse(entry.getKey());
            if (id != null && entry.getValue() instanceof JsonPrimitive count && count.isNumber()) {
                next.put(id, Math.max(0, count.getAsInt()));
            }
        }
        value = next;
        rebuild();
    }
}
