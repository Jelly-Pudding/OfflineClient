package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// An ordered list of registry entries such as blocks or items. Ids are
// stored in the config and a resolved set is rebuilt on change.
public final class RegistryListSetting<T> extends Setting<Set<Identifier>> implements PickList<T> {

    // Entities with no egg and no item of their own name.
    private static final Map<EntityType<?>, Item> STAND_INS = Map.ofEntries(
        Map.entry(EntityTypes.PLAYER, Items.PLAYER_HEAD),
        Map.entry(EntityTypes.ITEM, Items.BUNDLE),
        Map.entry(EntityTypes.EXPERIENCE_ORB, Items.EXPERIENCE_BOTTLE),
        Map.entry(EntityTypes.FISHING_BOBBER, Items.FISHING_ROD),
        Map.entry(EntityTypes.EYE_OF_ENDER, Items.ENDER_EYE),
        Map.entry(EntityTypes.LEASH_KNOT, Items.LEAD),
        Map.entry(EntityTypes.LIGHTNING_BOLT, Items.LIGHTNING_ROD.asList().getFirst()),
        Map.entry(EntityTypes.AREA_EFFECT_CLOUD, Items.LINGERING_POTION),
        Map.entry(EntityTypes.EVOKER_FANGS, Items.TOTEM_OF_UNDYING),
        Map.entry(EntityTypes.LLAMA_SPIT, Items.SNOWBALL),
        Map.entry(EntityTypes.SHULKER_BULLET, Items.SHULKER_SHELL),
        Map.entry(EntityTypes.FIREBALL, Items.FIRE_CHARGE),
        Map.entry(EntityTypes.SMALL_FIREBALL, Items.FIRE_CHARGE),
        Map.entry(EntityTypes.DRAGON_FIREBALL, Items.FIRE_CHARGE),
        Map.entry(EntityTypes.BREEZE_WIND_CHARGE, Items.BREEZE_ROD),
        Map.entry(EntityTypes.WITHER_SKULL, Items.WITHER_SKELETON_SKULL),
        Map.entry(EntityTypes.ENDER_DRAGON, Items.DRAGON_HEAD),
        Map.entry(EntityTypes.FALLING_BLOCK, Items.SAND),
        Map.entry(EntityTypes.OMINOUS_ITEM_SPAWNER, Items.TRIAL_KEY),
        Map.entry(EntityTypes.BLOCK_DISPLAY, Items.STRUCTURE_BLOCK),
        Map.entry(EntityTypes.ITEM_DISPLAY, Items.STRUCTURE_BLOCK),
        Map.entry(EntityTypes.TEXT_DISPLAY, Items.STRUCTURE_BLOCK));

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
        return Collections.unmodifiableSet(ids);
    }

    // The default entry of a defaulted registry stands for nothing and is left out.
    @Override
    public Collection<T> options() {
        Identifier defaultKey = registry instanceof DefaultedRegistry<T> defaulted
            ? defaulted.getDefaultKey() : null;
        List<T> all = new ArrayList<>();
        registry.stream().forEach(entry -> {
            Identifier id = registry.getKey(entry);
            if (id != null && !id.equals(defaultKey)) {
                all.add(entry);
            }
        });
        return all;
    }

    @Override
    public List<T> chosen() {
        return new ArrayList<>(resolved);
    }

    @Override
    public String idOf(T entry) {
        Identifier id = registry.getKey(entry);
        return id == null ? "unknown" : id.toString();
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

    public RegistryListSetting<T> onChange(Runnable onChange) {
        this.onChange = onChange;
        return this;
    }

    @Override
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
    @Override
    public ItemStack icon(T entry) {
        if (entry instanceof Block block) {
            return new ItemStack(block);
        }
        if (entry instanceof Item item) {
            return item.getDefaultInstance();
        }
        if (entry instanceof EntityType<?> type) {
            return entityIcon(type);
        }
        return ItemStack.EMPTY;
    }

    // The spawn egg where there is one and otherwise the item of the same
    // name such as a boat. The rest fall back to an item or a barrier.
    private static ItemStack entityIcon(EntityType<?> type) {
        Optional<Holder<Item>> egg = SpawnEggItem.byId(type);
        if (egg.isPresent()) {
            return new ItemStack(egg.get());
        }
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
            return new ItemStack(BuiltInRegistries.ITEM.getValue(id));
        }
        return new ItemStack(STAND_INS.getOrDefault(type, Items.BARRIER));
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
