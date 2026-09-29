package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Set;
import java.util.function.Predicate;

public final class InventoryUtil {

    private static final Minecraft MC = OfflineClient.MC;

    public static final int HOTBAR_SIZE = 9;

    // The main grid starts here in inventory index and network slot order alike.
    public static final int MAIN_START = HOTBAR_SIZE;

    // Every slot of the survival inventory.
    public static final int WHOLE_INVENTORY = 36;

    // Network slots of the survival inventory. The hotbar sits after the main grid
    // and a hotbar index maps to its start plus that index. Armour runs helmet first.
    public static final int HOTBAR_START = 36;
    public static final int ARMOR_START = 5;
    public static final int CHEST_SLOT = ARMOR_START + 1;
    public static final int OFFHAND_SLOT = 45;

    // Containers that keep whatever goes in. A hopper passes it on and a dropper or a
    // dispenser throws it out at the next redstone pulse.
    private static final Set<MenuType<?>> KEEPERS = Set.of(MenuType.GENERIC_9x1, MenuType.GENERIC_9x2,
        MenuType.GENERIC_9x3, MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6,
        MenuType.SHULKER_BOX);

    // The game names every ender chest screen with this key.
    private static final String ENDER_TITLE = "container.enderchest";

    private InventoryUtil() {
    }

    public static int networkSlot(int inventoryIndex) {
        if (inventoryIndex == Inventory.SLOT_OFFHAND) {
            return OFFHAND_SLOT;
        }
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

    // The game throws the click away unless the survival inventory is the live menu.
    // The creative screen swaps in a menu of its own where the slot numbers differ.
    public static boolean canClick() {
        if (MC.player.containerMenu != MC.player.inventoryMenu) {
            return false;
        }
        return !(MC.gui.screen() instanceof AbstractContainerScreen)
            || MC.gui.screen() instanceof InventoryScreen;
    }

    // True whilst a container stays open with no screen showing it. ChestLink keeps one that way.
    // The server then drops every click on the survival inventory and the kept menu takes them.
    public static boolean menuKept() {
        AbstractContainerMenu live = MC.player.containerMenu;
        return live != MC.player.inventoryMenu
            && !(MC.gui.screen() instanceof AbstractContainerScreen<?> shown && shown.getMenu() == live);
    }

    // Moves the stack at an inventory index into the offhand. An open container takes the swap
    // key in its own menu whether its screen shows or not. The survival inventory goes by cursor
    // clicks the stranded stack can put right. False when nothing moved.
    public static boolean toOffhand(int inventoryIndex, StrandedStack cursor) {
        if (MC.player.containerMenu != MC.player.inventoryMenu) {
            return swapToOffhand(inventoryIndex);
        }
        return cursorFree() && cursor.swap(networkSlot(inventoryIndex), OFFHAND_SLOT) != Swap.REFUSED;
    }

    // Any container menu reaches the offhand with the swap key. False when the menu shows no
    // such slot or something sits on the cursor.
    private static boolean swapToOffhand(int inventoryIndex) {
        AbstractContainerMenu live = openContainer();
        int slot = live == null ? -1 : MenuClicks.slotOf(live, inventoryIndex);
        if (slot == -1 || !live.getCarried().isEmpty()) {
            return false;
        }
        MenuClicks.click(live, slot, Inventory.SLOT_OFFHAND, ContainerInput.SWAP);
        return true;
    }

    // The live menu whilst it is a container the server has open. The creative screen swaps in
    // a menu of its own whose slot numbers the server reads as other slots. Null otherwise.
    private static AbstractContainerMenu openContainer() {
        AbstractContainerMenu live = MC.player.containerMenu;
        return live == MC.player.inventoryMenu || MC.gui.screen() instanceof CreativeModeInventoryScreen
            ? null : live;
    }

    // True when the survival inventory is up with nothing on the cursor.
    // Slot swaps and container clicks only land then.
    public static boolean inventoryFree() {
        return MC.gui.screen() == null
            && MC.player.containerMenu.containerId == 0
            && carried().isEmpty();
    }

    // True whilst a chest or furnace or any other container is open. Your own inventory
    // and the creative screen do not count.
    public static boolean containerOpen() {
        Screen screen = MC.gui.screen();
        return screen instanceof AbstractContainerScreen && !(screen instanceof InventoryScreen)
            && !(screen instanceof CreativeModeInventoryScreen);
    }

    // Plain storage only. A shift click there moves an inventory stack straight into the
    // container. Crafting and anvil and trade and mount screens put their own slots first.
    public static boolean isStorage(Screen screen) {
        return screen instanceof AbstractContainerScreen<?> shown && isStorage(shown.getMenu());
    }

    public static boolean isStorage(AbstractContainerMenu menu) {
        return keeps(menu) || menu.menuType == MenuType.HOPPER || menu.menuType == MenuType.GENERIC_3x3;
    }

    // Plain storage that keeps what it is given. Only these take items a module puts away.
    public static boolean keeps(AbstractContainerMenu menu) {
        return keeps(menu.menuType);
    }

    // A menu built without a type such as your own inventory is no container.
    public static boolean keeps(MenuType<?> type) {
        return type != null && KEEPERS.contains(type);
    }

    public static boolean isEnderChest(Screen screen) {
        return screen.getTitle().getContents() instanceof TranslatableContents title
            && ENDER_TITLE.equals(title.getKey());
    }

    public static ItemStack carried() {
        return MC.player.containerMenu.getCarried();
    }

    // Clicks are allowed and nothing sits on the cursor.
    public static boolean cursorFree() {
        return canClick() && carried().isEmpty();
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
        MenuClicks.click(MC.player.inventoryMenu, networkSlot, 0, ContainerInput.PICKUP);
    }

    // The number key swap. The stack trades places with the hotbar slot in one click.
    public static void swapWithHotbar(int networkSlot, int hotbarSlot) {
        MenuClicks.click(MC.player.inventoryMenu, networkSlot, hotbarSlot, ContainerInput.SWAP);
    }

    // Button one throws the whole stack in one click.
    public static void throwStack(int networkSlot) {
        MenuClicks.click(MC.player.inventoryMenu, networkSlot, 1, ContainerInput.THROW);
    }

    // Throws the whole stack at an inventory index through the menu that is live. A chest or a
    // furnace shows the hotbar as well. False whilst no menu can take the click.
    public static boolean throwFrom(int inventoryIndex) {
        if (cursorFree()) {
            throwStack(networkSlot(inventoryIndex));
            return true;
        }
        AbstractContainerMenu live = openContainer();
        int slot = live == null ? -1 : MenuClicks.slotOf(live, inventoryIndex);
        if (slot == -1 || !live.getCarried().isEmpty()) {
            return false;
        }
        MenuClicks.click(live, slot, 1, ContainerInput.THROW);
        return true;
    }

    public static int selectedSlot() {
        return MC.player.getInventory().getSelectedSlot();
    }

    // The first index up to the limit that passes the test. Minus one when none does.
    public static int findSlot(Predicate<ItemStack> test, int limit) {
        for (int i = 0; i < limit; i++) {
            if (test.test(MC.player.getInventory().getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    public static int findSlot(Item item, int limit) {
        return findSlot(stack -> stack.is(item), limit);
    }

    // How many of an item the first slots up to the limit hold between them.
    public static int count(Item item, int limit) {
        return count(stack -> stack.is(item), limit);
    }

    public static int count(Predicate<ItemStack> test, int limit) {
        int total = 0;
        for (int i = 0; i < limit; i++) {
            ItemStack stack = MC.player.getInventory().getItem(i);
            if (test.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
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

    // The network slot of the arrow a bow fires first or minus one. The offhand beats everything.
    public static int firstArrowSlot() {
        if (MC.player.getOffhandItem().is(ItemTags.ARROWS)) {
            return OFFHAND_SLOT;
        }
        int slot = findSlot(stack -> stack.is(ItemTags.ARROWS), WHOLE_INVENTORY);
        return slot == -1 ? -1 : networkSlot(slot);
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

    // Creative only. Puts a copy of the stack in an empty hotbar slot and holds it. A full hotbar
    // lends the held slot. Null outside creative.
    public static Conjured conjure(ItemStack stack) {
        if (MC.player == null || MC.gameMode == null || !MC.player.hasInfiniteMaterials()) {
            return null;
        }
        int held = selectedSlot();
        int slot = freeHotbarSlot(held);
        ItemStack before = MC.player.getInventory().getItem(slot).copy();
        setCreativeSlot(slot, stack.copy());
        MC.player.getInventory().setSelectedSlot(slot);
        return new Conjured(slot, before, held);
    }

    // The creative screen fills a slot the same way. The server takes any stack from a creative player.
    private static void setCreativeSlot(int slot, ItemStack stack) {
        MC.player.getInventory().setItem(slot, stack);
        MC.gameMode.handleCreativeModeItemAdd(stack, networkSlot(slot));
    }

    // A stack conjured into a hotbar slot. What the slot held before comes back on request.
    public record Conjured(int slot, ItemStack before, int held) {

        // Puts back what the slot held and the slot you had in hand.
        public void giveBack() {
            if (MC.player == null || MC.gameMode == null) {
                return;
            }
            setCreativeSlot(slot, before);
            MC.player.getInventory().setSelectedSlot(held);
        }
    }

    // Holds an item from anywhere in the inventory. A stack outside the hotbar
    // is moved in first and put back when the loan ends.
    public static final class HotbarLoan {

        private int previousSlot = -1;
        private int lentFrom = -1;
        private int lentTo = -1;

        // The hotbar slot the loan last selected.
        private int chosen = -1;

        // Selects the given inventory index and borrows it into the hotbar when
        // it is not already there. False when the swap could not be made.
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
                    if (!cursorFree()) {
                        return false;
                    }
                    // Only one stack can be away from home at a time.
                    if (lentFrom != -1 && !returnLoan()) {
                        return false;
                    }
                    // A full hotbar means the held slot gives up its place.
                    int hotbar = freeHotbarSlot(selectedSlot());
                    swapWithHotbar(slot, hotbar);
                    lentFrom = slot;
                    lentTo = hotbar;
                    slot = hotbar;
                }
            }
            if (previousSlot == -1) {
                previousSlot = selectedSlot();
            }
            chosen = slot;
            MC.player.getInventory().setSelectedSlot(slot);
            return true;
        }

        // Puts the item in the main hand from the slots below the limit. True once it is there.
        public boolean hold(Item item, int limit) {
            if (MC.player == null) {
                return false;
            }
            return MC.player.getMainHandItem().is(item) || select(findSlot(item, limit));
        }

        // True whilst the player is still on the slot the loan picked.
        public boolean stillMine() {
            return chosen != -1 && MC.player != null && selectedSlot() == chosen;
        }

        // Returns a borrowed stack and goes back to the slot the player had held.
        public void giveBack() {
            giveBack(true);
        }

        // A borrowed stack always goes home. The old slot only comes back when asked.
        public void giveBack(boolean reselect) {
            if (MC.player == null) {
                forget();
                return;
            }
            returnLoan();
            if (reselect && previousSlot != -1) {
                MC.player.getInventory().setSelectedSlot(previousSlot);
            }
            previousSlot = -1;
            chosen = -1;
        }

        // Gives the loan back unless the player is dying. Respawn hands out a fresh inventory.
        public void release() {
            release(true);
        }

        public void release(boolean reselect) {
            if (MC.player != null && MC.player.isDeadOrDying()) {
                forget();
            } else {
                giveBack(reselect);
            }
        }

        // False when the swap could not be sent. The loan then stays open.
        private boolean returnLoan() {
            if (lentFrom == -1) {
                return true;
            }
            if (!cursorFree()) {
                return false;
            }
            swapWithHotbar(lentFrom, lentTo);
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
            chosen = -1;
        }
    }

    // Remembers the hotbar slot a module took over. Each module owns its own instance.
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

        // The slot the player had before the first select. Minus one whilst none.
        public int previousSlot() {
            return previous;
        }

        public boolean isHolding() {
            return previous != -1;
        }
    }

    // Remembers the slot a stack left on the cursor came from. The stack goes
    // home again on the first call where the inventory will take a click.
    public static final class StrandedStack {

        private int slot = -1;

        // Records where a stack left on the cursor belongs.
        public void hold(int networkSlot) {
            slot = networkSlot;
        }

        // A swap that remembers the displaced stack when it had nowhere to go.
        public Swap swap(int fromNetworkSlot, int toNetworkSlot) {
            Swap result = InventoryUtil.swap(fromNetworkSlot, toNetworkSlot);
            if (result == Swap.STRANDED) {
                hold(fromNetworkSlot);
            }
            return result;
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
