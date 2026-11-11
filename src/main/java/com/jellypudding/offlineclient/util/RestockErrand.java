package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// Fetches what an owner wants out of the shulker boxes it carries and out of its ender chest. The
// errand sets each container down in turn and waits for it to come back into the inventory before
// the next goes down. A shulker box in the ender chest that holds what is wanted comes out with it
// and is opened beside you. It goes back into the ender chest before the errand ends.
public final class RestockErrand {

    // What an errand has set down beside you.
    private enum Down { NONE, SHULKER_BOX, ENDER_CHEST, RETURN }

    private static final Minecraft MC = OfflineClient.MC;

    // An errand takes no more steps than this. Each container takes one to go down and one more
    // when it first comes up into the hotbar. A box that keeps coming back unchanged or a stack
    // that will not come into the hotbar cannot hold it up for ever. Putting the boxes back always
    // has its two steps left.
    private static final int MOST_STEPS = 24;
    private static final int PUT_BACK_STEPS = 2;
    // A container mined back drops as an item nobody may pick up for ten ticks. The errand waits
    // this long and a round trip to the server for it before it counts it as lost.
    private static final int PICKUP_TICKS = 40;

    private final Restock restock;
    private final String owner;
    private final HotbarRoom room = new HotbarRoom();
    // What the errand wants and where it looks. Null wanted means no errand runs.
    private Predicate<ItemStack> wanted;
    private boolean fromShulkers;
    private boolean enderChestSearched;
    private Down down = Down.NONE;
    private int steps;
    // The ender chest goes down once to take back the boxes that came out of it.
    private boolean putBackTried;
    // What each box opened on this errand held as it was left. None is opened twice.
    private final List<List<ItemStack>> opened = new ArrayList<>();
    // What each box taken out of the ender chest holds now. They all go back in.
    private final List<List<ItemStack>> borrowed = new ArrayList<>();
    private final MenuClicks.Clicked clicked = new MenuClicks.Clicked();
    // Where the container mined back last lies until the inventory takes it back. What it is and
    // how many of its kind the inventory holds once it is back and the ticks waited for it.
    private BlockPos dropAt;
    private Predicate<ItemStack> dropKind;
    private int dropBack;
    private int dropTicks;
    // True once a container mined back never came back. The errand ends there.
    private boolean dropLost;

    // The owner's slot swap picks each container and the tool that mines it back. The words that
    // end "stops until" say when restocking comes back once a container would not open.
    public RestockErrand(InventoryUtil.SlotSwap slots, String owner, String stopsUntil) {
        this.restock = new Restock(slots, owner).stopsUntil(stopsUntil);
        this.owner = owner;
    }

    // True whilst another owner works with a container or waits for one to come back. Nothing of
    // this owner goes down meanwhile.
    public boolean busyElsewhere() {
        return restock.busyElsewhere();
    }

    // Ends whatever is under way and forgets a refusal. For a fresh start of the owner.
    public void reset() {
        restock.reset();
        wanted = null;
        dropAt = null;
        room.giveBack();
    }

    // An ender chest only comes back to a Silk Touch pickaxe. Without one it would stay where it went down.
    private static boolean canBringBackEnderChest() {
        return ItemUtil.bestToolSlot(Blocks.ENDER_CHEST.defaultBlockState(), 1, RestockErrand::silkTouch,
            InventoryUtil.HOTBAR_SIZE) != -1;
    }

    // True when the ender chest you carry may hold what is wanted but no Silk Touch pickaxe in the
    // hotbar could bring it back.
    public static boolean needsPickaxe(Predicate<ItemStack> wants) {
        return !canBringBackEnderChest() && InventoryUtil.findSlot(Items.ENDER_CHEST, InventoryUtil.WHOLE_INVENTORY) != -1
            && rememberedMayHold(wants);
    }

    // Where an errand sets a container down. Null when no spot will do.
    public static BlockPos errandSpot() {
        return PlacedBox.spot(RestockErrand::dry);
    }

    // True when an errand for stacks the test accepts would find a container worth setting down.
    // The ender chest counts whilst a Silk Touch pickaxe can bring it back and its last opening
    // showed something wanted in it. One never opened on this server may hold anything.
    public boolean couldRun(Predicate<ItemStack> wants, boolean shulkers, boolean enderChest) {
        if (restock.refused()) {
            return false;
        }
        return shulkers
            && InventoryUtil.findSlot(stack -> Restock.holds(stack, wants), InventoryUtil.WHOLE_INVENTORY) != -1
            || enderChest && canBringBackEnderChest() && rememberedMayHold(wants)
            && InventoryUtil.findSlot(Items.ENDER_CHEST, InventoryUtil.WHOLE_INVENTORY) != -1;
    }

    // Starts an errand for stacks the test accepts. The test is asked afresh before every click
    // and may stop wanting more as stacks come in.
    public void startErrand(Predicate<ItemStack> wants, boolean shulkers, boolean enderChest) {
        wanted = wants;
        fromShulkers = shulkers;
        enderChestSearched = !enderChest;
        down = Down.NONE;
        steps = 0;
        putBackTried = false;
        dropAt = null;
        dropLost = false;
        opened.clear();
        borrowed.clear();
    }

    // One tick of the errand. Shulker boxes that hold what is wanted come first and then the ender
    // chest. Boxes taken out of the ender chest are opened next and go back in last. Each
    // container is back in the inventory before the next goes down. False once the errand is over.
    public boolean runErrand(boolean rotate) {
        if (wanted == null) {
            return false;
        }
        if (restock.active()) {
            if (!restock.tick(down == Down.RETURN ? this::putBack : this::takeOut, rotate, rotate)) {
                minedBack(down == Down.SHULKER_BOX);
                down = Down.NONE;
            }
            return true;
        }
        if (dropAt != null && awaitingDrop()) {
            return true;
        }
        if (restock.refused() || dropLost) {
            finishErrand();
            return false;
        }
        if (restock.busyElsewhere()) {
            return true;
        }
        // A container that is opened keeps one slot free to come back into.
        boolean room = steps < MOST_STEPS && InventoryUtil.emptySlots() > 0;
        if (room && setDownFor(Down.SHULKER_BOX, this::worthOpening, rotate)) {
            return true;
        }
        if (room && !enderChestSearched && canBringBackEnderChest() && rememberedMayHold(wanted)
            && setDownFor(Down.ENDER_CHEST, RestockErrand::isEnderChest, rotate)) {
            enderChestSearched = down == Down.ENDER_CHEST;
            return true;
        }
        if (!borrowed.isEmpty() && !putBackTried && steps < MOST_STEPS + PUT_BACK_STEPS && canBringBackEnderChest()
            && setDownFor(Down.RETURN, RestockErrand::isEnderChest, rotate)) {
            putBackTried = down == Down.RETURN;
            return true;
        }
        finishErrand();
        return false;
    }

    // Ends an errand part way. The container standing or still on the ground is named and the
    // screen it opened is shut.
    public void endErrand() {
        boolean standing = restock.active();
        if (standing && restock.opened() && MC.player != null && MC.player.containerMenu != MC.player.inventoryMenu) {
            MC.player.closeContainer();
        }
        restock.end();
        if (standing) {
            tellLeftStanding();
        }
        if (dropAt != null && MC.player != null) {
            ChatUtil.error(owner + " left the container it mined back at " + BlockUtil.text(dropAt) + " on the ground.");
        }
        dropAt = null;
        if (wanted != null) {
            finishErrand();
        }
    }

    private void finishErrand() {
        if (!borrowed.isEmpty()) {
            ChatUtil.error(owner + " could not put every shulker box it took out of the ender chest back.");
        }
        room.giveBack();
        wanted = null;
        down = Down.NONE;
        dropAt = null;
        opened.clear();
        borrowed.clear();
        restock.release();
    }

    private boolean setDownFor(Down kind, Predicate<ItemStack> which, boolean rotate) {
        if (!restock.setDown(which, index -> room.bring(index, RestockErrand::silkTouch), rotate,
            RestockErrand::errandSpot)) {
            return false;
        }
        steps++;
        // A step that only brought a container into the hotbar set nothing down.
        if (restock.active()) {
            down = kind;
            clicked.clear();
            restock.claim();
        }
        return true;
    }

    // The container's turn is over. One that still stands is named. One that was mined back
    // lies on the ground a few ticks before the inventory can take it back.
    private void minedBack(boolean shulkerBox) {
        BlockPos spot = restock.spot();
        if (MC.level == null || PlacedBox.standing(spot)) {
            tellLeftStanding();
            return;
        }
        dropKind = shulkerBox ? stack -> ItemUtil.isShulkerBox(stack.getItem()) : RestockErrand::isEnderChest;
        dropBack = carried(dropKind) + 1;
        dropTicks = 0;
        dropAt = spot;
    }

    // True whilst the container mined back is still on its way into the inventory. One that
    // never comes is named and ends the errand.
    private boolean awaitingDrop() {
        if (carried(dropKind) >= dropBack) {
            dropAt = null;
            return false;
        }
        if (++dropTicks <= PICKUP_TICKS + ServerInfo.answerTicks()) {
            return true;
        }
        boolean stands = MC.level != null && PlacedBox.standing(dropAt);
        ChatUtil.error(owner + (stands ? " left the container at " + BlockUtil.text(dropAt) + " standing."
            : " could not pick up the container it mined back at " + BlockUtil.text(dropAt) + "."));
        dropAt = null;
        dropLost = true;
        return false;
    }

    private static int carried(Predicate<ItemStack> kind) {
        return InventoryUtil.countWithOffhand(kind, InventoryUtil.WHOLE_INVENTORY);
    }

    // A shulker box worth opening holds what is wanted and is one of yours whilst the errand
    // searches them or one out of the ender chest. None is opened twice.
    private boolean worthOpening(ItemStack stack) {
        if (!Restock.holds(stack, wanted)) {
            return false;
        }
        List<ItemStack> holding = Restock.contents(stack);
        return (fromShulkers || indexOf(borrowed, holding) != -1) && indexOf(opened, holding) == -1;
    }

    // The ender chest is only worth opening when its last opening showed a wanted stack or a box
    // holding one. One never opened on this server may hold anything.
    private static boolean rememberedMayHold(Predicate<ItemStack> wants) {
        List<ItemStack> remembered = EnderChest.INSTANCE.contents();
        return remembered.isEmpty()
            || remembered.stream().anyMatch(stack -> wants.test(stack) || Restock.holds(stack, wants));
    }

    // Shift clicks one wanted stack a tick out of the open container. Out of the ender chest a
    // shulker box comes too for a wanted item that no box taken out before holds. One slot stays
    // free for the container to come back into and one more for each box taken out and not yet
    // opened. A stack the server threw back is passed over until it changes.
    private boolean takeOut(AbstractContainerMenu menu) {
        int spare = InventoryUtil.emptySlots() - 1 - keptForBorrowed();
        if (spare > 0) {
            for (int slot : MenuClicks.containerSlots(menu)) {
                ItemStack stack = menu.slots.get(slot).getItem();
                if (stack.isEmpty() || clicked.thrownBack(slot, stack)) {
                    continue;
                }
                // A box taken out needs a slot of its own and later one for a stack out of it.
                boolean borrow = down == Down.ENDER_CHEST && spare > 1 && Restock.holds(stack, wanted)
                    && bringsNewKind(stack);
                if (borrow || wanted.test(stack)) {
                    List<ItemStack> holding = Restock.contents(stack);
                    clicked.note(slot, stack);
                    if (MenuClicks.quickMoved(menu, slot) && borrow) {
                        borrowed.add(holding);
                    }
                    return true;
                }
            }
        }
        if (down == Down.SHULKER_BOX) {
            leaving(MenuClicks.containerItems(menu));
        }
        return false;
    }

    // Boxes taken out of the ender chest that are still to be opened. The one open now is not
    // among them.
    private int keptForBorrowed() {
        int kept = 0;
        for (List<ItemStack> holding : borrowed) {
            if (indexOf(opened, holding) == -1
                && !(down == Down.SHULKER_BOX && ItemUtil.sameContents(holding, restock.setDownHolding()))) {
                kept++;
            }
        }
        return kept;
    }

    // A box taken out of the ender chest is only worth it for a wanted item none taken before holds.
    private boolean bringsNewKind(ItemStack stack) {
        return Restock.contents(stack).stream().filter(inside -> !inside.isEmpty() && wanted.test(inside))
            .anyMatch(inside -> borrowed.stream().flatMap(List::stream).noneMatch(taken -> taken.is(inside.getItem())));
    }

    // Remembers how a box was left. A box out of the ender chest is known again by what it held.
    private void leaving(List<ItemStack> holding) {
        opened.add(holding);
        int index = indexOf(borrowed, restock.setDownHolding());
        if (index != -1) {
            borrowed.set(index, holding);
        }
    }

    // Shift clicks one box taken out of the ender chest back into it a tick.
    private boolean putBack(AbstractContainerMenu menu) {
        for (int slot : MenuClicks.inventorySlots(menu)) {
            ItemStack stack = menu.slots.get(slot).getItem();
            if (!ItemUtil.isShulkerBox(stack.getItem()) || clicked.thrownBack(slot, stack)) {
                continue;
            }
            int index = indexOf(borrowed, Restock.contents(stack));
            if (index == -1) {
                continue;
            }
            clicked.note(slot, stack);
            if (MenuClicks.quickMoved(menu, slot)) {
                borrowed.remove(index);
            }
            return true;
        }
        return false;
    }

    // Says where a container set down still stands. It could not be mined back. There is nothing
    // to look at once the world has gone.
    private void tellLeftStanding() {
        BlockPos spot = restock.spot();
        if (MC.level != null && PlacedBox.standing(spot)) {
            ChatUtil.error(owner + " left the container at " + BlockUtil.text(spot) + " standing.");
        }
    }

    // Water or lava in the spot or over it would flow in once the container is mined and carry its
    // drop off or burn it. Lava beside it would burn it as well.
    private static boolean dry(BlockPos spot) {
        if (!BlockUtil.state(spot).getFluidState().isEmpty() || !BlockUtil.state(spot.above()).getFluidState().isEmpty()) {
            return false;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (BlockUtil.state(spot.relative(side)).getFluidState().is(FluidTags.LAVA)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEnderChest(ItemStack stack) {
        return stack.is(Items.ENDER_CHEST);
    }

    private static boolean silkTouch(ItemStack stack) {
        return ItemUtil.enchantLevel(Enchantments.SILK_TOUCH, stack) > 0;
    }

    // The first box of the list that holds exactly the same slot by slot. Boxes are told apart by
    // what they hold. Minus one when none does.
    private static int indexOf(List<List<ItemStack>> boxes, List<ItemStack> holding) {
        for (int i = 0; i < boxes.size(); i++) {
            if (ItemUtil.sameContents(boxes.get(i), holding)) {
                return i;
            }
        }
        return -1;
    }
}
