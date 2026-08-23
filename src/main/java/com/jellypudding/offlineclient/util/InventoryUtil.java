package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

public final class InventoryUtil {

    private static final Minecraft MC = OfflineClient.MC;

    // A hotbar slot maps to this network slot plus its index.
    private static final int HOTBAR_START = 36;

    public static final int OFFHAND_SLOT = 45;

    private InventoryUtil() {
    }

    // The hotbar sits after the rest in network slot order.
    public static int networkSlot(int inventoryIndex) {
        return inventoryIndex < 9 ? HOTBAR_START + inventoryIndex : inventoryIndex;
    }

    public enum Swap {
        // Nothing moved.
        REFUSED,
        // The item moved and the cursor came away empty.
        DONE,
        // The item moved but something is still on the cursor.
        STRANDED
    }

    // The game throws the click away when a different container is open.
    public static boolean canClick() {
        if (MC.player.containerMenu.containerId != 0) {
            return false;
        }
        return !(MC.gui.screen() instanceof AbstractContainerScreen)
            || MC.gui.screen() instanceof InventoryScreen
            || MC.gui.screen() instanceof CreativeModeInventoryScreen;
    }

    public static ItemStack carried() {
        return MC.player.containerMenu.getCarried();
    }

    // Moves one slot into another with the three clicks a player makes.
    public static Swap swap(int fromNetworkSlot, int toNetworkSlot) {
        click(fromNetworkSlot);
        if (carried().isEmpty()) {
            return Swap.REFUSED;
        }
        click(toNetworkSlot);
        if (!carried().isEmpty()) {
            // Whatever the new item displaced goes into the slot it came from.
            click(fromNetworkSlot);
        }
        return carried().isEmpty() ? Swap.DONE : Swap.STRANDED;
    }

    // A pickup click on the survival inventory container.
    public static void click(int networkSlot) {
        if (MC.player == null || MC.gameMode == null) {
            return;
        }
        MC.gameMode.handleContainerInput(0, networkSlot, 0, ContainerInput.PICKUP, MC.player);
    }

    public static int selectedSlot() {
        return MC.player.getInventory().getSelectedSlot();
    }

    // The slot already in hand wins. Minus one when nothing matches.
    public static int hotbarSlot(Predicate<ItemStack> test) {
        int selected = selectedSlot();
        if (test.test(MC.player.getInventory().getItem(selected))) {
            return selected;
        }
        for (int i = 0; i < 9; i++) {
            if (test.test(MC.player.getInventory().getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    // An empty hotbar slot or minus one when every one is taken.
    public static int freeHotbarSlot() {
        return freeHotbarSlot(-1);
    }

    public static int freeHotbarSlot(int whenFull) {
        for (int i = 0; i < 9; i++) {
            if (MC.player.getInventory().getItem(i).isEmpty()) {
                return i;
            }
        }
        return whenFull;
    }

    /**
     * Remembers the hotbar slot a module took over. Each module owns its own
     * instance.
     */
    public static final class SlotSwap {

        private int previous = -1;

        public void select(int slot) {
            if (MC.player == null || slot < 0 || slot > 8) {
                return;
            }
            int selected = selectedSlot();
            if (selected == slot) {
                return;
            }
            if (previous == -1) {
                previous = selected;
            }
            MC.player.getInventory().setSelectedSlot(slot);
            sync();
        }

        public void restore() {
            if (previous != -1 && MC.player != null) {
                MC.player.getInventory().setSelectedSlot(previous);
                sync();
            }
            previous = -1;
        }

        // Vanilla only syncs the held slot when it next handles an interaction.
        // The instant mining path sends its own packets so it would miss the swap.
        // This defers to vanilla so the sent slot is tracked and never sent twice.
        private static void sync() {
            if (MC.gameMode != null && MC.player != null && MC.player.connection != null) {
                MC.gameMode.ensureHasSentCarriedItem();
            }
        }

        public void forget() {
            previous = -1;
        }

        public boolean isHolding() {
            return previous != -1;
        }
    }
}
