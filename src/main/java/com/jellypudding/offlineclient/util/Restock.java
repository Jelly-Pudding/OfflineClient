package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

// The container goes down beside the player and the owner's clicks take the wanted stacks out of it
// before it is mined back. One that would not open stops restocking from containers until the owner
// starts afresh. One owner at a time works with containers.
public final class Restock {

    // How the owner brings a stack from the backpack into the hotbar. True when it acted.
    @FunctionalInterface
    public interface ToHotbar {
        boolean move(int inventoryIndex);
    }

    private static final Minecraft MC = OfflineClient.MC;

    // The restock whose errand has a container down or on its way back. No other owner sets one
    // down meanwhile.
    private static Restock working;

    private final PlacedBox box;
    private final String owner;
    private boolean refused;
    // The words that end "stops until" when a container would not open. Null says no more.
    private String refusalLasts;
    private BlockPos spot;
    private List<ItemStack> setDownHolding = List.of();

    // The owner's slot swap picks the container and the tool that mines it back.
    public Restock(InventoryUtil.SlotSwap slots, String owner) {
        this.box = new PlacedBox(slots);
        this.owner = owner;
    }

    Restock stopsUntil(String words) {
        refusalLasts = words;
        return this;
    }

    public boolean active() {
        return box.active();
    }

    public boolean refused() {
        return refused;
    }

    public boolean busyElsewhere() {
        return box.othersAtWork() || working != null && working != this;
    }

    // Ends whatever is under way. A refusal is kept.
    public void end() {
        box.end();
    }

    // Ends whatever is under way and forgets a refusal. For a fresh start of the owner.
    public void reset() {
        box.end();
        refused = false;
        release();
    }

    public static boolean holds(ItemStack stack, Predicate<ItemStack> wanted) {
        if (!ItemUtil.isShulkerBox(stack.getItem())) {
            return false;
        }
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        return contents != null && contents.nonEmptyItemCopyStream().anyMatch(wanted);
    }

    // A container in the backpack is brought into the hotbar first. True when something was done
    // this tick.
    public boolean setDown(Predicate<ItemStack> which, ToHotbar toHotbar, boolean rotate) {
        return setDown(which, toHotbar, rotate, PlacedBox::spot);
    }

    boolean setDown(Predicate<ItemStack> which, ToHotbar toHotbar, boolean rotate, Supplier<BlockPos> where) {
        if (busyElsewhere()) {
            return false;
        }
        int slot = InventoryUtil.findSlot(which, InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1) {
            return false;
        }
        if (slot >= InventoryUtil.HOTBAR_SIZE) {
            return toHotbar.move(slot);
        }
        BlockPos at = where.get();
        List<ItemStack> holding = contents(MC.player.getInventory().getItem(slot));
        if (at == null || !box.setDown(slot, at, rotate)) {
            return false;
        }
        spot = at;
        setDownHolding = holding;
        return true;
    }

    // False once the container's turn is over.
    public boolean tick(PlacedBox.Clicks clicks, boolean rotatePlace, boolean rotateMine) {
        boolean going = box.tick(clicks, rotatePlace, rotateMine);
        if (!going && box.wouldNotOpen()) {
            refused = true;
            ChatUtil.error(owner + " could not open the container it set down. Restocking from containers stops"
                + (refusalLasts == null ? "." : " until " + refusalLasts + "."));
        }
        return going;
    }

    boolean opened() {
        return box.opened();
    }

    BlockPos spot() {
        return spot;
    }

    List<ItemStack> setDownHolding() {
        return setDownHolding;
    }

    void claim() {
        working = this;
    }

    void release() {
        if (working == this) {
            working = null;
        }
    }

    static List<ItemStack> contents(ItemStack stack) {
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        return contents == null ? List.of() : contents.itemCopies().toList();
    }
}
