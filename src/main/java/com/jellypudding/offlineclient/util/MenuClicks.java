package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

// Clicks on the slots of an open container menu. InventoryUtil covers the player's own inventory.
public final class MenuClicks {

    private static final Minecraft MC = OfflineClient.MC;

    public static void click(AbstractContainerMenu menu, int slot, int button, ContainerInput kind) {
        MC.gameMode.handleContainerInput(menu.containerId, slot, button, kind, MC.player);
    }

    public static void quickMove(AbstractContainerMenu menu, int slot) {
        click(menu, slot, 0, ContainerInput.QUICK_MOVE);
    }

    // False when the stack stayed put because the other side had no room.
    public static boolean quickMoved(AbstractContainerMenu menu, int slot) {
        quickMove(menu, slot);
        return menu.slots.get(slot).getItem().isEmpty();
    }

    // Minus one when no slot from the start onwards passes.
    public static int firstSlot(AbstractContainerMenu menu, int start, Predicate<ItemStack> test) {
        for (int i = start; i < menu.slots.size(); i++) {
            if (test.test(menu.slots.get(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    // Picks the stack up and drops items into the target one right click at a time.
    // Whatever is left goes back where it came from.
    public static void moveSome(AbstractContainerMenu menu, int from, int to, int count) {
        if (!menu.getCarried().isEmpty()) {
            return;
        }
        click(menu, from, 0, ContainerInput.PICKUP);
        for (int i = 0; i < count && !menu.getCarried().isEmpty(); i++) {
            click(menu, to, 1, ContainerInput.PICKUP);
        }
        if (!menu.getCarried().isEmpty()) {
            click(menu, from, 0, ContainerInput.PICKUP);
        }
    }

    private MenuClicks() {
    }
}
