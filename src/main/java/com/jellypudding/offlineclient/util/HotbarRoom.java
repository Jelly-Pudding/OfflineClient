package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// Makes room in the hotbar for a stack out of the backpack. A full hotbar lends one of its slots
// and the stack that slot held goes back into it once the owner is done.
public final class HotbarRoom {

    private record Lent(int hotbar, int index, ItemStack stack) {
    }

    private static final Minecraft MC = OfflineClient.MC;

    private final List<Lent> lent = new ArrayList<>();

    // With no slot empty the first slot from the held one round whose stack the owner need not keep
    // is lent. False when every slot has to be kept.
    public boolean bring(int inventoryIndex, Predicate<ItemStack> keep) {
        int hotbar = InventoryUtil.freeHotbarSlot();
        if (hotbar == -1) {
            hotbar = lendable(keep);
            if (hotbar == -1) {
                return false;
            }
            lent.add(new Lent(hotbar, inventoryIndex, MC.player.getInventory().getItem(hotbar).copy()));
        }
        InventoryUtil.swapWithHotbar(InventoryUtil.networkSlot(inventoryIndex), hotbar);
        return true;
    }

    // A stack that has moved on since stays where it is. One that grew or shrank in the backpack
    // still counts as the same.
    public void giveBack() {
        if (MC.player != null && InventoryUtil.cursorFree()) {
            for (int i = lent.size() - 1; i >= 0; i--) {
                Lent one = lent.get(i);
                ItemStack now = MC.player.getInventory().getItem(one.index());
                if (ItemStack.isSameItemSameComponents(now, one.stack())) {
                    InventoryUtil.swapWithHotbar(InventoryUtil.networkSlot(one.index()), one.hotbar());
                }
            }
        }
        lent.clear();
    }

    private static int lendable(Predicate<ItemStack> keep) {
        int held = InventoryUtil.selectedSlot();
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            int slot = (held + i) % InventoryUtil.HOTBAR_SIZE;
            if (!keep.test(MC.player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }
}
