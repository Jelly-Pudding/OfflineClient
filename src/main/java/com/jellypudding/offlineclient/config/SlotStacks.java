package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// The stacks of a container as a file keeps them. Each one is written with its slot and the
// empty slots are left out.
final class SlotStacks {

    private SlotStacks() {
    }

    static JsonArray encode(List<ItemStack> items, RegistryOps<JsonElement> ops) {
        JsonArray entries = new JsonArray();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            int index = i;
            ItemStack.CODEC.encodeStart(ops, stack).result().ifPresent(json -> {
                JsonObject slot = new JsonObject();
                slot.addProperty("slot", index);
                slot.add("item", json);
                entries.add(slot);
            });
        }
        return entries;
    }

    // The stacks back in their slots with the empty ones in place. A slot past the size and a
    // stack the game cannot read are left out.
    static List<ItemStack> decode(JsonArray entries, int size, RegistryOps<JsonElement> ops) {
        List<ItemStack> items = new ArrayList<>(Collections.nCopies(size, ItemStack.EMPTY));
        for (JsonElement entry : entries) {
            JsonObject slot = entry.getAsJsonObject();
            int index = slot.get("slot").getAsInt();
            if (index >= 0 && index < size) {
                ItemStack.CODEC.parse(ops, slot.get("item")).result().ifPresent(stack -> items.set(index, stack));
            }
        }
        return List.copyOf(items);
    }
}
