package com.jellypudding.offlineclient.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.Map;
import java.util.TreeMap;

// Named inventory layouts saved to offlineclient/loadouts.json. Each one maps an
// inventory index to the kind of item that belongs there.
public final class LoadoutStore extends KeyedStore<LoadoutStore.Loadout> {

    public record Loadout(String name, int key, Map<Integer, String> slots) implements KeyedStore.Keyed<Loadout> {

        public Loadout {
            slots = Map.copyOf(slots);
        }

        @Override
        public Loadout withKey(int key) {
            return new Loadout(name, key, slots);
        }
    }

    private static LoadoutStore instance;

    private LoadoutStore() {
        super("loadouts.json", LoadoutStore::read, LoadoutStore::write);
    }

    public static synchronized LoadoutStore get() {
        if (instance == null) {
            instance = new LoadoutStore();
        }
        return instance;
    }

    // What a slot is saved as. The item and for a potion or a tipped arrow the potion too.
    // Null for an empty slot.
    public static String kindOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return item;
        }
        return contents.potion().map(Holder::getRegisteredName).map(potion -> item + " " + potion).orElse(item);
    }

    private static Loadout read(String name, int key, JsonObject saved) {
        Map<Integer, String> slots = new TreeMap<>();
        for (Map.Entry<String, JsonElement> slot : saved.getAsJsonObject("slots").entrySet()) {
            slots.put(Integer.parseInt(slot.getKey()), slot.getValue().getAsString());
        }
        return new Loadout(name, key, slots);
    }

    private static void write(Loadout loadout, JsonObject saved) {
        JsonObject slots = new JsonObject();
        new TreeMap<>(loadout.slots()).forEach((index, kind) -> slots.addProperty(String.valueOf(index), kind));
        saved.add("slots", slots);
    }
}
