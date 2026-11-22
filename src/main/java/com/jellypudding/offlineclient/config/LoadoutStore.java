package com.jellypudding.offlineclient.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.setting.Bind;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.Map;
import java.util.TreeMap;

public final class LoadoutStore extends KeyedStore<LoadoutStore.Loadout> {

    public record Loadout(String name, Bind key, Map<Integer, String> slots) implements KeyedStore.Keyed<Loadout> {

        public Loadout {
            slots = Map.copyOf(slots);
        }

        @Override
        public Loadout withKey(Bind key) {
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

    // The item and for a potion or a tipped arrow the potion too.
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

    private static Loadout read(String name, Bind key, JsonObject saved) {
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
