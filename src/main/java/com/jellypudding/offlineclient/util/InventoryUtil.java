package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

public final class InventoryUtil {

    private static final Minecraft MC = OfflineClient.MC;

    // A hotbar slot maps to this network slot plus its index.
    private static final int HOTBAR_START = 36;

    public static final int HOTBAR_SIZE = 9;

    public static final int OFFHAND_SLOT = 45;

    private InventoryUtil() {
    }

    // The hotbar sits after the rest in network slot order.
    public static int networkSlot(int inventoryIndex) {
        return inventoryIndex < HOTBAR_SIZE ? HOTBAR_START + inventoryIndex : inventoryIndex;
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

    /**
     * True when the survival inventory is up with nothing on the cursor. Slot
     * swaps and container clicks only land then.
     */
    public static boolean inventoryFree() {
        return MC.gui.screen() == null
            && MC.player.containerMenu.containerId == 0
            && carried().isEmpty();
    }

    /**
     * Plain storage only. Crafting and anvil and trade and mount screens put
     * their own slots first and refuse a click into them.
     */
    public static boolean isStorage(Screen screen) {
        return screen instanceof ContainerScreen
            || screen instanceof ShulkerBoxScreen
            || screen instanceof HopperScreen
            || screen instanceof DispenserScreen;
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
        for (int i = 0; i < HOTBAR_SIZE; i++) {
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
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (MC.player.getInventory().getItem(i).isEmpty()) {
                return i;
            }
        }
        return whenFull;
    }

    /**
     * Holds an item from anywhere in the inventory. A stack outside the hotbar
     * is moved in first and put back when the loan ends.
     */
    public static final class HotbarLoan {

        private int previousSlot = -1;
        private int lentFrom = -1;
        private int lentTo = -1;

        /**
         * Selects the given inventory index and borrows it into the hotbar when
         * it is not already there. False when the swap could not be made.
         */
        public boolean select(int inventorySlot) {
            if (MC.player == null || inventorySlot < 0) {
                return false;
            }
            int slot = inventorySlot;
            if (slot >= HOTBAR_SIZE) {
                if (lentFrom == slot) {
                    // Already borrowed. A second swap would send it home again.
                    slot = lentTo;
                } else {
                    if (!canClick() || !carried().isEmpty()) {
                        return false;
                    }
                    // Only one stack can be away from home at a time.
                    if (lentFrom != -1 && !returnLoan()) {
                        return false;
                    }
                    // A full hotbar means the held slot gives up its place.
                    int hotbar = freeHotbarSlot(selectedSlot());
                    MC.gameMode.handleContainerInput(0, slot, hotbar, ContainerInput.SWAP, MC.player);
                    lentFrom = slot;
                    lentTo = hotbar;
                    slot = hotbar;
                }
            }
            if (previousSlot == -1) {
                previousSlot = selectedSlot();
            }
            MC.player.getInventory().setSelectedSlot(slot);
            return true;
        }

        // Returns a borrowed stack and goes back to the slot the player had held.
        public void giveBack() {
            if (MC.player == null) {
                forget();
                return;
            }
            returnLoan();
            if (previousSlot != -1) {
                MC.player.getInventory().setSelectedSlot(previousSlot);
                previousSlot = -1;
            }
        }

        // False when the swap could not be sent. The loan then stays open.
        private boolean returnLoan() {
            if (lentFrom == -1) {
                return true;
            }
            if (!canClick() || !carried().isEmpty()) {
                return false;
            }
            MC.gameMode.handleContainerInput(0, lentFrom, lentTo, ContainerInput.SWAP, MC.player);
            lentFrom = -1;
            lentTo = -1;
            return true;
        }

        // True whilst a stack is still out of its home slot.
        public boolean isLent() {
            return lentFrom != -1;
        }

        // Drops the bookkeeping without touching the inventory. For a respawn.
        public void forget() {
            previousSlot = -1;
            lentFrom = -1;
            lentTo = -1;
        }
    }

    /**
     * Remembers the hotbar slot a module took over. Each module owns its own
     * instance.
     */
    public static final class SlotSwap {

        private int previous = -1;

        // The slot this module last selected.
        private int taken = -1;

        public void select(int slot) {
            if (MC.player == null || slot < 0 || slot >= HOTBAR_SIZE) {
                return;
            }
            taken = slot;
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

        // True whilst the player is still on the slot this module chose.
        public boolean stillMine() {
            return MC.player != null && taken != -1 && selectedSlot() == taken;
        }

        // Gives the slot back only if the player has not picked another since.
        public void restoreIfMine() {
            if (stillMine()) {
                restore();
            }
            forget();
        }

        // The instant mining path sends raw packets and never trips vanilla's own sync.
        private static void sync() {
            if (MC.gameMode != null && MC.player != null && MC.player.connection != null) {
                MC.gameMode.ensureHasSentCarriedItem();
            }
        }

        public void forget() {
            previous = -1;
            taken = -1;
        }

        public boolean isHolding() {
            return previous != -1;
        }
    }

    /**
     * Remembers the slot a stack left on the cursor came from. The stack goes
     * home again on the first call where the inventory will take a click.
     */
    public static final class StrandedStack {

        private int slot = -1;

        // Notes where a stack left on the cursor belongs.
        public void hold(int networkSlot) {
            slot = networkSlot;
        }

        // True once the cursor is clear. Tries again on every call until it is.
        public boolean recover() {
            if (slot == -1 || carried().isEmpty()) {
                slot = -1;
                return true;
            }
            if (!canClick()) {
                return false;
            }
            click(slot);
            if (carried().isEmpty()) {
                slot = -1;
                return true;
            }
            return false;
        }

        // One last attempt and then the bookkeeping goes. For a disable or a respawn.
        public void giveBack() {
            recover();
            slot = -1;
        }

        public boolean isHolding() {
            return slot != -1;
        }
    }
}
