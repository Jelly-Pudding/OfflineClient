package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class InventoryUtil {

    private static final Minecraft MC = OfflineClient.MC;

    public static final int HOTBAR_SIZE = 9;

    // The hotbar slots named the way the number keys pick them.
    public static final List<String> HOTBAR_NAMES = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9");

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

    // The container menu a click opened once its items are in. The menu live as the click went
    // out is never it. The items follow the screen in a packet of their own and the menu counts
    // from nought until then. Null whilst no such menu shows.
    public static AbstractContainerMenu openedSince(int menuBefore) {
        AbstractContainerMenu live = MC.player.containerMenu;
        return containerOpen() && live.containerId != menuBefore && live.getStateId() != 0 ? live : null;
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

    // True for a worn piece the game will not let you take off. Only a creative player may.
    public static boolean stuckOn(ItemStack worn) {
        return !MC.player.isCreative()
            && EnchantmentHelper.has(worn, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
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

    // The same with the offhand counted as well.
    public static int countWithOffhand(Item item, int limit) {
        return countWithOffhand(stack -> stack.is(item), limit);
    }

    public static int countWithOffhand(Predicate<ItemStack> test, int limit) {
        ItemStack offhand = MC.player.getOffhandItem();
        return count(test, limit) + (test.test(offhand) ? offhand.getCount() : 0);
    }

    // How many slots of the hotbar and the main grid hold nothing.
    public static int emptySlots() {
        int empty = 0;
        for (int i = 0; i < WHOLE_INVENTORY; i++) {
            if (MC.player.getInventory().getItem(i).isEmpty()) {
                empty++;
            }
        }
        return empty;
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
    // is moved in first and put back when the loan ends. Loans nest. A later loan that lends
    // its stack into the slot an earlier loan or slot swap chose covers it. Whilst covered the
    // earlier one sends nothing home and picks no slot. Once the later stack goes home the
    // earlier one is whole again.
    public static final class HotbarLoan {

        // The loan whose stack sits in each hotbar slot. An entry only counts whilst its loan is
        // still open there under the current connection.
        private static final HotbarLoan[] LENT = new HotbarLoan[HOTBAR_SIZE];

        private int previousSlot = -1;
        private int lentFrom = -1;
        private int lentTo = -1;

        // The hotbar slot the loan last selected and the loan whose stack sat there then.
        private int chosen = -1;
        private HotbarLoan chosenOver;

        // The loan whose stack the lent one pushed out of the hotbar.
        private HotbarLoan lentOver;

        // A give back came whilst the stack could not go home. A loan lent over it sends it home
        // straight after its own.
        private boolean owed;

        // The connection the loan was made under. A respawn or a portal keeps it and only a leave
        // hands out another. Held weakly to keep no closed connection alive.
        private WeakReference<ClientPacketListener> session = new WeakReference<>(null);

        // Selects the given inventory index and borrows it into the hotbar when
        // it is not already there. False when the swap could not be made.
        public boolean select(int inventorySlot) {
            return select(inventorySlot, -1);
        }

        // The same but a full hotbar never gives up the spared slot. The one beside it goes
        // instead. Minus one spares none.
        public boolean select(int inventorySlot, int spared) {
            int slot = borrow(inventorySlot, spared);
            if (slot == -1) {
                return false;
            }
            // Picking the slot it already holds leaves a cover in place.
            if (slot != chosen || !covered()) {
                chosenOver = lentInto(slot);
            }
            chosen = slot;
            MC.player.getInventory().setSelectedSlot(slot);
            return true;
        }

        // Brings the given inventory index into the hotbar and leaves the held slot as it is. The
        // hotbar slot it sits in or minus one when the swap could not be made.
        public int borrow(int inventorySlot) {
            return borrow(inventorySlot, -1);
        }

        // The same with a slot spared as select spares it. A give back still selects the slot
        // held before the loan.
        public int borrow(int inventorySlot, int spared) {
            if (MC.player == null || inventorySlot < 0) {
                return -1;
            }
            forgetAfterLeave();
            int slot = inventorySlot;
            if (slot >= HOTBAR_SIZE) {
                if (lentFrom == slot) {
                    // Already borrowed. A second swap would send it home again. Under another
                    // loan's stack it is out of reach.
                    if (lendCovered()) {
                        return -1;
                    }
                    slot = lentTo;
                    owed = false;
                } else {
                    if (!cursorFree()) {
                        return -1;
                    }
                    // Only one stack can be away from home at a time.
                    if (lentFrom != -1 && !returnLoan()) {
                        return -1;
                    }
                    // A full hotbar means the held slot gives up its place.
                    int hotbar = freeHotbarSlot(selectedSlot());
                    if (hotbar == spared && !MC.player.getInventory().getItem(hotbar).isEmpty()) {
                        hotbar = (hotbar + 1) % HOTBAR_SIZE;
                    }
                    lentOver = lentInto(hotbar);
                    swapWithHotbar(slot, hotbar);
                    lentFrom = slot;
                    lentTo = hotbar;
                    LENT[hotbar] = this;
                    slot = hotbar;
                }
            }
            if (previousSlot == -1) {
                previousSlot = selectedSlot();
            }
            return slot;
        }

        // Puts the item in the main hand from the slots below the limit. True once it is there.
        public boolean hold(Item item, int limit) {
            if (MC.player == null) {
                return false;
            }
            return MC.player.getMainHandItem().is(item) || select(findSlot(item, limit));
        }

        // True whilst the player is still on the slot the loan picked and no later loan covers it.
        public boolean stillMine() {
            forgetAfterLeave();
            return chosen != -1 && MC.player != null && selectedSlot() == chosen && !covered();
        }

        // Returns a borrowed stack and goes back to the slot the player had held.
        public void giveBack() {
            giveBack(true);
        }

        // A borrowed stack always goes home. The old slot only comes back when asked. No slot is
        // picked whilst a later loan's stack sits in the slot this one picked or lent into. Its own
        // stack then goes home straight after that loan's.
        public void giveBack(boolean reselect) {
            if (MC.player == null) {
                forget();
                return;
            }
            boolean covered = covered() || lendCovered();
            owed = !returnLoan();
            if (reselect && !covered && previousSlot != -1) {
                MC.player.getInventory().setSelectedSlot(previousSlot);
            }
            previousSlot = -1;
            chosen = -1;
            chosenOver = null;
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

        // For a pause whilst something else has the hands. Short of a death a covered loan keeps
        // everything until the loan over it gives its stack back. Otherwise it lets go as release
        // does and the slot held before only comes back whilst the player is still on the one picked.
        public void standAside() {
            boolean dying = MC.player == null || MC.player.isDeadOrDying();
            if (dying || !covered()) {
                release(stillMine());
            }
        }

        // False when the swap could not be sent or another loan's stack sits where this one lent.
        // The loan then stays open.
        private boolean returnLoan() {
            if (lentFrom == -1) {
                return true;
            }
            if (lendCovered() || !cursorFree()) {
                return false;
            }
            swapWithHotbar(lentFrom, lentTo);
            int slot = lentTo;
            LENT[slot] = lentOver;
            lentFrom = -1;
            lentTo = -1;
            lentOver = null;
            owed = false;
            // The stack this loan pushed out is back. A loan that waited to send it home goes now.
            HotbarLoan under = lentInto(slot);
            if (under != null && under.owed) {
                under.returnLoan();
            }
            return true;
        }

        // True whilst a stack is still out of its home slot.
        public boolean isLent() {
            forgetAfterLeave();
            return lentFrom != -1;
        }

        // True whilst a later loan's stack sits in the slot this one picked.
        private boolean covered() {
            forgetAfterLeave();
            return coveredSince(chosen, chosenOver, this);
        }

        // True whilst another loan's stack sits where this one lent its own.
        private boolean lendCovered() {
            HotbarLoan over = lentFrom == -1 ? null : lentInto(lentTo);
            return over != null && over != this;
        }

        // True whilst the slot holds the stack of a loan that is neither the taker nor the one whose
        // stack sat there when the slot was taken.
        private static boolean coveredSince(int slot, HotbarLoan atTake, HotbarLoan taker) {
            HotbarLoan over = slot == -1 ? null : lentInto(slot);
            return over != null && over != atTake && over != taker;
        }

        // The open loan whose stack sits in the hotbar slot. Null whilst none does.
        private static HotbarLoan lentInto(int slot) {
            HotbarLoan loan = LENT[slot];
            return loan != null && loan.lentTo == slot && loan.session.get() == MC.getConnection() ? loan : null;
        }

        // Another server or a later visit holds another inventory. A swap back would scramble two of
        // its slots.
        private void forgetAfterLeave() {
            ClientPacketListener now = MC.getConnection();
            if (session.get() != now) {
                forget();
                session = new WeakReference<>(now);
            }
        }

        // Drops the bookkeeping without touching the inventory. For a respawn or a leave. Within one
        // visit a stack owed home from under another loan's stack still goes once that loan gives its
        // own back. A leave drops everything.
        public void forget() {
            previousSlot = -1;
            chosen = -1;
            chosenOver = null;
            if (owed && session.get() == MC.getConnection() && lendCovered()) {
                return;
            }
            int slot = lentTo;
            HotbarLoan under = lentOver;
            lentFrom = -1;
            lentTo = -1;
            lentOver = null;
            owed = false;
            // A loan beneath that waited for this one to go home first would wait for ever.
            if (under != null && under.owed && under.lentTo == slot) {
                under.forget();
            }
        }
    }

    // Remembers the hotbar slot a module took over. Each module owns its own instance.
    public static final class SlotSwap {

        private int previous = -1;

        // The slot this module last selected and the loan whose stack sat there then.
        private int taken = -1;
        private HotbarLoan takenOver;

        public void select(int slot) {
            if (MC.player == null || slot < 0 || slot >= HOTBAR_SIZE) {
                return;
            }
            // Picking the slot it already holds leaves a cover in place.
            if (slot != taken || !covered()) {
                takenOver = HotbarLoan.lentInto(slot);
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

        // Picks the slot held before again unless a later loan covers the slot taken.
        public void restore() {
            if (previous != -1 && MC.player != null && !covered()) {
                MC.player.getInventory().setSelectedSlot(previous);
                sync();
            }
            previous = -1;
            taken = -1;
            takenOver = null;
        }

        // True whilst the player is still on the slot this module chose and no later loan covers it.
        public boolean stillMine() {
            return MC.player != null && taken != -1 && selectedSlot() == taken && !covered();
        }

        // True whilst a loan made after the select lends its stack into the slot taken.
        public boolean covered() {
            return HotbarLoan.coveredSince(taken, takenOver, null);
        }

        // Gives the slot back only if the player has not picked another since.
        public void restoreIfMine() {
            if (stillMine()) {
                restore();
            }
            forget();
        }

        // For a pause whilst something else has the hands. A covered slot is kept whole until the
        // loan over it gives its stack back.
        public void standAside() {
            if (!covered()) {
                restoreIfMine();
            }
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
            takenOver = null;
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
