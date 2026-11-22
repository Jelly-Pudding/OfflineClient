package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

// Ids are stored in the config and a resolved set is rebuilt on change.
public final class RegistryListSetting<T> extends Setting<Set<Identifier>> implements PickList<T> {

    // The noun finishes the sentence Every such and such.
    public record Group<T>(String label, String noun, List<T> members) {
    }

    private final Registry<T> registry;

    // Read from other threads and replaced whole.
    private volatile Set<T> resolved = Set.of();

    private Runnable onChange;

    private Predicate<T> offered = entry -> true;

    public RegistryListSetting(String name, String description, Registry<T> registry,
                               Collection<? extends T> defaults) {
        super(name, description, toIds(registry, defaults));
        this.registry = registry;
        this.value = new LinkedHashSet<>(defaultValue);
        rebuild();
    }

    private static <T> Set<Identifier> toIds(Registry<T> registry, Collection<? extends T> defaults) {
        Set<Identifier> ids = new LinkedHashSet<>();
        for (T entry : defaults) {
            Identifier id = registry.getKey(entry);
            if (id != null) {
                ids.add(id);
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    public RegistryListSetting<T> only(Predicate<T> offered) {
        this.offered = offered;
        return this;
    }

    @Override
    public Collection<T> options() {
        return RegistryEntries.offered(registry, offered);
    }

    public List<Group<T>> groups() {
        return RegistryEntries.groups(registry, options());
    }

    @Override
    public List<T> chosen() {
        return new ArrayList<>(resolved);
    }

    @Override
    public String idOf(T entry) {
        return RegistryEntries.idOf(registry, entry);
    }

    @Override
    public Set<Identifier> getValue() {
        return Collections.unmodifiableSet(value);
    }

    // Unknown ids are kept in the config but not here.
    public Set<T> resolved() {
        return resolved;
    }

    @Override
    public int size() {
        return value.size();
    }

    public boolean contains(T entry) {
        return resolved.contains(entry);
    }

    @Override
    public boolean isChosen(T entry) {
        Identifier id = registry.getKey(entry);
        return id != null && value.contains(id);
    }

    @Override
    public void add(T entry) {
        Identifier id = registry.getKey(entry);
        if (id != null && value.add(id)) {
            rebuild();
            changed();
        }
    }

    // One rebuild for the lot. Adding a whole registry one entry at a time would crawl.
    @Override
    public void addAll(Collection<? extends T> entries) {
        boolean added = false;
        for (T entry : entries) {
            Identifier id = registry.getKey(entry);
            added |= id != null && value.add(id);
        }
        if (added) {
            rebuild();
            changed();
        }
    }

    @Override
    public void clear() {
        value.clear();
        rebuild();
        changed();
    }

    @Override
    public void remove(T entry) {
        Identifier id = registry.getKey(entry);
        if (id != null && value.remove(id)) {
            rebuild();
            changed();
        }
    }

    @Override
    public void removeAll(Collection<? extends T> entries) {
        boolean removed = false;
        for (T entry : entries) {
            Identifier id = registry.getKey(entry);
            removed |= id != null && value.remove(id);
        }
        if (removed) {
            rebuild();
            changed();
        }
    }

    public RegistryListSetting<T> onChange(Runnable onChange) {
        this.onChange = onChange;
        return this;
    }

    @Override
    public String displayName(T entry) {
        return RegistryEntries.displayName(registry, entry);
    }

    @Override
    public ItemStack icon(T entry) {
        return RegistryEntries.icon(entry);
    }

    @Override
    public void setValue(Set<Identifier> value) {
        this.value = new LinkedHashSet<>(value);
        rebuild();
        changed();
    }

    @Override
    public void reset() {
        value = new LinkedHashSet<>(defaultValue);
        rebuild();
        changed();
    }

    @Override
    public boolean isDefault() {
        return sameOrder(value, defaultValue);
    }

    private void rebuild() {
        Set<T> next = new LinkedHashSet<>();
        for (Identifier id : value) {
            // A defaulted registry hands back its default entry for unknown ids.
            if (registry.containsKey(id)) {
                next.add(registry.getValue(id));
            }
        }
        // Set.copyOf loses order and some modules rank by pick order.
        resolved = Collections.unmodifiableSet(next);
    }

    private void changed() {
        if (onChange != null) {
            onChange.run();
        }
    }

    @Override
    public JsonElement toJson() {
        JsonArray array = new JsonArray();
        for (Identifier id : value) {
            array.add(id.toString());
        }
        return array;
    }

    @Override
    public void fromJson(JsonElement json) {
        if (!json.isJsonArray()) {
            return;
        }
        Set<Identifier> next = new LinkedHashSet<>();
        for (JsonElement element : json.getAsJsonArray()) {
            if (element.isJsonPrimitive()) {
                Identifier id = Identifier.tryParse(element.getAsString());
                if (id != null) {
                    next.add(id);
                }
            }
        }
        value = next;
        rebuild();
        changed();
    }
}
