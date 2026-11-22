package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

// A stack already in a hand comes first and then the slots the take from choice allows. A stack
// outside the hotbar is borrowed into it and goes home when the builder lets go. A creative player
// who carries none gets the block out of thin air for the one click.
public final class BlockSupply {

    private static final Minecraft MC = OfflineClient.MC;

    // The slot of a stack that sits in none. The offhand holds it or it is yet to be conjured.
    private static final int NO_SLOT = -1;

    public record Source(ItemStack stack, InteractionHand hand, int slot) {
    }

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private InventoryUtil.Conjured conjured;

    // A creative player gets a stack of the given item when nothing carried fits and the item is
    // not null. Null when there is nothing to place with.
    public Source find(Predicate<ItemStack> fits, TakeFrom from, Item creative) {
        LocalPlayer player = MC.player;
        if (fits.test(player.getMainHandItem())) {
            return new Source(player.getMainHandItem(), InteractionHand.MAIN_HAND, InventoryUtil.selectedSlot());
        }
        if (fits.test(player.getOffhandItem())) {
            return new Source(player.getOffhandItem(), InteractionHand.OFF_HAND, NO_SLOT);
        }
        int slot = InventoryUtil.findSlot(fits, from.limit());
        if (slot != -1) {
            return new Source(player.getInventory().getItem(slot), InteractionHand.MAIN_HAND, slot);
        }
        return creative != null && player.hasInfiniteMaterials()
            ? new Source(new ItemStack(creative), InteractionHand.MAIN_HAND, NO_SLOT) : null;
    }

    // False when the inventory would not take the swap this tick.
    public boolean hold(Source source) {
        if (source.hand() == InteractionHand.OFF_HAND) {
            return true;
        }
        if (source.slot() == NO_SLOT) {
            conjured = InventoryUtil.conjure(source.stack());
            return conjured != null;
        }
        if (!loan.select(source.slot())) {
            return false;
        }
        if (MC.player.getMainHandItem().is(source.stack().getItem())) {
            return true;
        }
        // The stack sits in the slot a borrowed one came out of and the loan answers for that
        // slot with what it borrowed. Sent home the stack is back in the hotbar for the next tick.
        loan.giveBack();
        return false;
    }

    // After the click. A conjured stack goes and the slot gets back what it held.
    public void clicked() {
        if (conjured != null) {
            conjured.giveBack();
            conjured = null;
        }
    }

    // Sends a borrowed stack home. The slot the player held before is picked again only when
    // asked. Something else that took the hand meanwhile keeps the slot it chose.
    public void release(boolean reselect) {
        clicked();
        loan.release(reselect);
    }

    // Drops the bookkeeping after a respawn handed out a fresh inventory.
    public void forget() {
        conjured = null;
        loan.forget();
    }
}
