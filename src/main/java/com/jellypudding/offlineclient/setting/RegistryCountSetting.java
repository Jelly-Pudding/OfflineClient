package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

// Entries picked from a registry each with a number of its own. A number belongs to its entry and
// never to a place in the list. Ids and numbers are stored in the config.
public final class RegistryCountSetting<T> extends Setting<Map<Identifier, Integer>> implements CountedPickList<T> {

    private final Registry<T> registry;
    // The number a new pick starts with.
    private final ToIntFunction<T> firstCount;
    // No number is kept below this.
    private final int lowest;

    // Picked entries with their numbers. Read from other threads and replaced whole.
    private volatile Map<T, Integer> resolved = Map.of();

    public RegistryCountSetting(String name, String description, Registry<T> registry, Map<T, Integer> defaults,
                                ToIntFunction<T> firstCount, int lowest) {
        super(name, description, toIds(registry, defaults));
        this.registry = registry;
        this.firstCount = firstCount;
        this.lowest = lowest;
        this.value = new LinkedHashMap<>(defaultValue);
        rebuild();
    }

    private static <T> Map<Identifier, Integer> toIds(Registry<T> registry, Map<T, Integer> counts) {
        Map<Identifier, Integer> ids = new LinkedHashMap<>();
        counts.forEach((entry, count) -> {
            Identifier id = registry.getKey(entry);
            if (id != null) {
                ids.put(id, count);
            }
        });
        return Collections.unmodifiableMap(ids);
    }

    // Minus one for an entry that was never picked.
    public int countOf(T entry) {
        return resolved.getOrDefault(entry, -1);
    }

    // The picked entries and their numbers in pick order.
    public Map<T, Integer> counts() {
        return resolved;
    }

    @Override
    public Collection<T> options() {
        return RegistryEntries.offered(registry, entry -> true);
    }

    @Override
    public List<T> chosen() {
        return new ArrayList<>(resolved.keySet());
    }

    @Override
    public boolean isChosen(T entry) {
        Identifier id = registry.getKey(entry);
        return id != null && value.containsKey(id);
    }

    @Override
    public void add(T entry) {
        Identifier id = registry.getKey(entry);
        if (id != null && value.putIfAbsent(id, startOf(entry)) == null) {
            rebuild();
        }
    }

    @Override
    public void addAll(Collection<? extends T> entries) {
        for (T entry : entries) {
            Identifier id = registry.getKey(entry);
            if (id != null) {
                value.putIfAbsent(id, startOf(entry));
            }
        }
        rebuild();
    }

    private int startOf(T entry) {
        return Math.max(lowest, firstCount.applyAsInt(entry));
    }

    @Override
    public void remove(T entry) {
        Identifier id = registry.getKey(entry);
        if (id != null && value.remove(id) != null) {
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
    public int count(T entry) {
        return resolved.getOrDefault(entry, lowest);
    }

    @Override
    public void setCount(T entry, int count) {
        Identifier id = registry.getKey(entry);
        if (id != null && value.containsKey(id)) {
            value.put(id, Math.max(lowest, count));
            rebuild();
        }
    }

    @Override
    public String displayName(T entry) {
        return RegistryEntries.displayName(registry, entry);
    }

    @Override
    public String idOf(T entry) {
        return RegistryEntries.idOf(registry, entry);
    }

    @Override
    public ItemStack icon(T entry) {
        return RegistryEntries.icon(entry);
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

    // An id the game does not know keeps its number in the config and is left out here. A
    // defaulted registry hands back its default entry for such an id.
    private void rebuild() {
        Map<T, Integer> next = new LinkedHashMap<>();
        value.forEach((id, count) -> {
            if (registry.containsKey(id)) {
                next.put(registry.getValue(id), count);
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
                next.put(id, Math.max(lowest, count.getAsInt()));
            }
        }
        value = next;
        rebuild();
    }
}
