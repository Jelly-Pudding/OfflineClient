package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

// Choices in the order the player prefers them. Each one can be switched off. The
// ClickGUI draws every choice as a row to drag into place and click on or off.
public final class RankSetting<E extends Enum<E>> extends Setting<List<RankSetting.Entry<E>>> {

    public record Entry<E>(E choice, boolean on) {
    }

    private final Class<E> type;
    private final Function<E, ItemStack> icon;
    private final Map<E, ItemStack> icons;

    // Every constant of the enum in its declared order and switched on.
    public RankSetting(String name, String description, Class<E> type, Function<E, ItemStack> icon) {
        this(name, description, type, icon, choice -> true);
    }

    // Every constant in its declared order. Only the ones the test picks start switched on.
    public RankSetting(String name, String description, Class<E> type, Function<E, ItemStack> icon,
                       Predicate<E> startsOn) {
        super(name, description, Arrays.stream(type.getEnumConstants())
            .map(choice -> new Entry<>(choice, startsOn.test(choice))).toList());
        this.type = type;
        this.icon = icon;
        this.icons = new EnumMap<>(type);
    }

    public int size() {
        return value.size();
    }

    public Entry<E> get(int index) {
        return value.get(index);
    }

    // The choices switched on with the favourite first.
    public List<E> ranked() {
        return value.stream().filter(Entry::on).map(Entry::choice).toList();
    }

    public void move(int from, int to) {
        List<Entry<E>> order = new ArrayList<>(value);
        order.add(to, order.remove(from));
        value = List.copyOf(order);
    }

    public void toggle(int index) {
        List<Entry<E>> order = new ArrayList<>(value);
        Entry<E> entry = order.get(index);
        order.set(index, new Entry<>(entry.choice(), !entry.on()));
        value = List.copyOf(order);
    }

    // Null for choices with no picture. Each stack is built once on first draw.
    public ItemStack icon(int index) {
        return icon == null ? null : icons.computeIfAbsent(get(index).choice(), icon);
    }

    public String label(int index) {
        return EnumSetting.label(get(index).choice());
    }

    // What a command types for each choice.
    public List<String> ids() {
        return Arrays.stream(type.getEnumConstants())
            .map(choice -> choice.name().toLowerCase(Locale.ROOT)).toList();
    }

    // The named choices go first in the order given and switched on. The rest follow
    // switched off. False when a word names no choice.
    public boolean setFromWords(String text) {
        List<Entry<E>> order = new ArrayList<>();
        for (String word : text.trim().split("\\s+")) {
            E choice = choiceNamed(word);
            if (choice == null) {
                return false;
            }
            if (order.stream().noneMatch(entry -> entry.choice() == choice)) {
                order.add(new Entry<>(choice, true));
            }
        }
        for (Entry<E> entry : value) {
            if (order.stream().noneMatch(kept -> kept.choice() == entry.choice())) {
                order.add(new Entry<>(entry.choice(), false));
            }
        }
        value = List.copyOf(order);
        return true;
    }

    private E choiceNamed(String word) {
        for (E choice : type.getEnumConstants()) {
            if (choice.name().equalsIgnoreCase(word)) {
                return choice;
            }
        }
        return null;
    }

    @Override
    public String getValueString() {
        List<String> names = ranked().stream().map(EnumSetting::label).toList();
        return names.isEmpty() ? "nothing" : String.join(" then ", names);
    }

    @Override
    public JsonElement toJson() {
        JsonArray array = new JsonArray();
        for (Entry<E> entry : value) {
            JsonObject object = new JsonObject();
            object.addProperty("choice", entry.choice().name());
            object.addProperty("on", entry.on());
            array.add(object);
        }
        return array;
    }

    // A choice the save does not know yet joins the end as it starts.
    @Override
    public void fromJson(JsonElement json) {
        if (!json.isJsonArray()) {
            return;
        }
        List<Entry<E>> order = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            E choice = object.get("choice") instanceof JsonPrimitive name ? choiceNamed(name.getAsString()) : null;
            if (choice != null && order.stream().noneMatch(entry -> entry.choice() == choice)) {
                boolean on = !(object.get("on") instanceof JsonPrimitive flag) || flag.getAsBoolean();
                order.add(new Entry<>(choice, on));
            }
        }
        for (Entry<E> entry : defaultValue) {
            if (order.stream().noneMatch(kept -> kept.choice() == entry.choice())) {
                order.add(entry);
            }
        }
        value = List.copyOf(order);
    }
}
