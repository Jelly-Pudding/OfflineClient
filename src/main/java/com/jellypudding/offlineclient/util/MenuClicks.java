package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

import java.util.function.Predicate;

// Clicks on the slots of an open container menu. InventoryUtil covers the player's own inventory.
public final class MenuClicks {

    private static final Minecraft MC = OfflineClient.MC;

    // The trade menu holds the payment in its first two slots and what you get in the third.
    // MerchantMenu keeps its own names for them out of reach.
    private static final int TRADE_PAYMENT1_SLOT = 0;
    private static final int TRADE_PAYMENT2_SLOT = 1;
    public static final int TRADE_RESULT_SLOT = 2;

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

    // The first slot of the player's own backpack or hotbar that passes. Minus one when none does.
    // Armour and the offhand never count.
    public static int firstInventorySlot(AbstractContainerMenu menu, Predicate<ItemStack> test) {
        for (Slot slot : menu.slots) {
            if (isInventorySlot(slot) && test.test(slot.getItem())) {
                return slot.index;
            }
        }
        return -1;
    }

    public static boolean isInventorySlot(Slot slot) {
        return slot.container == MC.player.getInventory()
            && slot.getContainerSlot() < InventoryUtil.WHOLE_INVENTORY;
    }

    // Trades two slots. A slot of your own hotbar or offhand trades in one number key
    // click. Any other pair takes three pickup clicks and never leaves the cursor full.
    public static void swap(AbstractContainerMenu menu, int a, int b) {
        int key = swapKey(menu, b);
        if (key != -1) {
            click(menu, a, key, ContainerInput.SWAP);
            return;
        }
        key = swapKey(menu, a);
        if (key != -1) {
            click(menu, b, key, ContainerInput.SWAP);
            return;
        }
        click(menu, a, 0, ContainerInput.PICKUP);
        click(menu, b, 0, ContainerInput.PICKUP);
        click(menu, a, 0, ContainerInput.PICKUP);
        if (!menu.getCarried().isEmpty()) {
            click(menu, b, 0, ContainerInput.PICKUP);
        }
    }

    // The number key that reaches a slot. Minus one unless it is your own hotbar or offhand.
    private static int swapKey(AbstractContainerMenu menu, int slot) {
        Slot target = menu.slots.get(slot);
        if (target.container != MC.player.getInventory()) {
            return -1;
        }
        int index = target.getContainerSlot();
        return index < InventoryUtil.HOTBAR_SIZE || index == Inventory.SLOT_OFFHAND ? index : -1;
    }

    // Presses a button of the menu such as an enchanting row or a stonecutter recipe.
    // The menu takes it at once the way the screen does and the server hears of it.
    public static void pressButton(AbstractContainerMenu menu, int button) {
        menu.clickMenuButton(MC.player, button);
        MC.gameMode.handleInventoryButtonClick(menu.containerId, button);
    }

    // Asks the server to fill the crafting grid from the recipe book with as many sets as the
    // inventory holds. It is the shift click on a recipe.
    public static void placeRecipe(AbstractContainerMenu menu, RecipeDisplayId recipe) {
        MC.gameMode.handlePlaceRecipe(menu.containerId, recipe, true);
    }

    // Picks a trade and moves its payment across from the inventory. False when the inventory
    // cannot pay. Picking the first trade then shows any other trade the payment covers.
    public static boolean selectTrade(MerchantMenu menu, int index) {
        menu.setSelectionHint(index);
        menu.tryMoveItems(index);
        MC.player.connection.send(new ServerboundSelectTradePacket(index));
        return menu.getOffers().get(index).satisfiedBy(menu.getSlot(TRADE_PAYMENT1_SLOT).getItem(),
            menu.getSlot(TRADE_PAYMENT2_SLOT).getItem());
    }

    // Types a name into the anvil. The server only hears of a name that changed.
    public static void rename(AnvilMenu menu, String name) {
        if (menu.setItemName(name)) {
            MC.player.connection.send(new ServerboundRenameItemPacket(name));
        }
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
