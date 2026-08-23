package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An ordered list of registry entries such as blocks or items. Ids are
 * stored in the config and a resolved set is rebuilt on change.
 */
public final class RegistryListSetting<T> extends Setting<Set<Identifier>> {

    private final Registry<T> registry;

    // Resolved entries. Read from other threads and replaced whole.
    private volatile Set<T> resolved = Set.of();

    // Runs after every change to the list.
    private Runnable onChange;

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
        return java.util.Collections.unmodifiableSet(ids);
    }

    public Registry<T> getRegistry() {
        return registry;
    }

    // Unknown ids are kept in the config but not here.
    public Set<T> resolved() {
        return resolved;
    }

    public int size() {
        return value.size();
    }

    public boolean contains(T entry) {
        return resolved.contains(entry);
    }

    public boolean isChosen(T entry) {
        Identifier id = registry.getKey(entry);
        return id != null && value.contains(id);
    }

    public void add(T entry) {
        Identifier id = registry.getKey(entry);
        if (id != null && value.add(id)) {
            rebuild();
            changed();
        }
    }

    public void clear() {
        value.clear();
        rebuild();
    }

    public void remove(T entry) {
        Identifier id = registry.getKey(entry);
        if (id != null && value.remove(id)) {
            rebuild();
            changed();
        }
    }

    public RegistryListSetting<T> onChange(Runnable onChange) {
        this.onChange = onChange;
        return this;
    }

    public String displayName(T entry) {
        if (entry instanceof Block block) {
            return block.getName().getString();
        }
        if (entry instanceof Item item) {
            return item.getName(item.getDefaultInstance()).getString();
        }
        if (entry instanceof EntityType<?> type) {
            return type.getDescription().getString();
        }
        if (entry instanceof MobEffect effect) {
            return effect.getDisplayName().getString();
        }
        Identifier id = registry.getKey(entry);
        return id == null ? "unknown" : id.getPath().replace('_', ' ');
    }

    // Empty when the entry has no item form.
    public ItemStack icon(T entry) {
        if (entry instanceof Block block) {
            return new ItemStack(block);
        }
        if (entry instanceof Item item) {
            return item.getDefaultInstance();
        }
        if (entry instanceof EntityType<?> type) {
            return SpawnEggItem.byId(type).map(ItemStack::new).orElse(ItemStack.EMPTY);
        }
        return ItemStack.EMPTY;
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

    private void rebuild() {
        Set<T> next = new LinkedHashSet<>();
        for (Identifier id : value) {
            // A defaulted registry hands back its default entry for unknown ids.
            if (registry.containsKey(id)) {
                next.add(registry.getValue(id));
            }
        }
        // Set.copyOf loses order and some modules rank by pick order.
        resolved = java.util.Collections.unmodifiableSet(next);
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
