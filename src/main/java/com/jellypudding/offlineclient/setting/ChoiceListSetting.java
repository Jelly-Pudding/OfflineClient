package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

// An ordered list of names picked from whatever the supplier offers at the
// time. A name picked earlier stays even after the offer stops holding it.
public final class ChoiceListSetting extends Setting<Set<String>> implements PickList<String> {

    private final Supplier<Collection<String>> options;

    // Runs after every change to the list.
    private Runnable onChange;

    public ChoiceListSetting(String name, String description, Supplier<Collection<String>> options) {
        this(name, description, options, List.of());
    }

    // Names picked before the player touches the list.
    public ChoiceListSetting(String name, String description, Supplier<Collection<String>> options,
                             Collection<String> defaults) {
        super(name, description, Collections.unmodifiableSet(new LinkedHashSet<>(defaults)));
        this.options = options;
        this.value = new LinkedHashSet<>(defaultValue);
    }

    public ChoiceListSetting onChange(Runnable onChange) {
        this.onChange = onChange;
        return this;
    }

    @Override
    public Set<String> getValue() {
        return Collections.unmodifiableSet(value);
    }

    @Override
    public void setValue(Set<String> value) {
        this.value = new LinkedHashSet<>(value);
        changed();
    }

    @Override
    public void reset() {
        value = new LinkedHashSet<>(defaultValue);
        changed();
    }

    // A name chosen earlier stays on the list even if the offer has not shown it yet.
    @Override
    public Collection<String> options() {
        Set<String> all = new LinkedHashSet<>(options.get());
        all.addAll(value);
        return all;
    }

    @Override
    public List<String> chosen() {
        return new ArrayList<>(value);
    }

    @Override
    public boolean isChosen(String entry) {
        return value.contains(entry);
    }

    public boolean contains(String entry) {
        return value.contains(entry);
    }

    @Override
    public void add(String entry) {
        if (value.add(entry)) {
            changed();
        }
    }

    @Override
    public void addAll(Collection<? extends String> entries) {
        if (value.addAll(entries)) {
            changed();
        }
    }

    @Override
    public void remove(String entry) {
        if (value.remove(entry)) {
            changed();
        }
    }

    @Override
    public void clear() {
        value.clear();
        changed();
    }

    @Override
    public int size() {
        return value.size();
    }

    @Override
    public String displayName(String entry) {
        return entry;
    }

    @Override
    public String idOf(String entry) {
        return entry;
    }

    @Override
    public ItemStack icon(String entry) {
        return ItemStack.EMPTY;
    }

    @Override
    public String getValueString() {
        return size() + " chosen";
    }

    private void changed() {
        if (onChange != null) {
            onChange.run();
        }
    }

    @Override
    public JsonElement toJson() {
        JsonArray array = new JsonArray();
        for (String entry : value) {
            array.add(entry);
        }
        return array;
    }

    @Override
    public void fromJson(JsonElement json) {
        if (!json.isJsonArray()) {
            return;
        }
        Set<String> next = new LinkedHashSet<>();
        for (JsonElement element : json.getAsJsonArray()) {
            if (element.isJsonPrimitive()) {
                next.add(element.getAsString());
            }
        }
        value = next;
        changed();
    }
}
