package com.jellypudding.offlineclient.modules.world.mapart;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.MapStations;
import com.jellypudding.offlineclient.config.MapStations.Role;
import com.jellypudding.offlineclient.config.MapStations.Station;
import com.jellypudding.offlineclient.path.Trip;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.MapPicture;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.StationVisit.Step;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

// The materials carried and the marked chests they come from. A trip fetches what it needs from
// the chests and leftovers go back into the chests that hold them or into the dump.
public final class StationStock {

    // A material a chest was found without.
    private record Out(BlockPos chest, Item item) {
    }

    private static final Minecraft MC = OfflineClient.MC;

    // Ticks a chest found without a material is passed over for it.
    private static final int EMPTY_TICKS = 600;

    private final Printer printer;
    private final Trip trip;

    private Map<Item, Integer> carried = Map.of();
    private final Cooldowns<Out> outOf = new Cooldowns<>();
    private final Set<BlockPos> fullChests = new HashSet<>();
    // Ticks spent waiting for a material every chest is out of.
    private int starved;
    // Set once a chest had more of a material than the inventory could take this trip.
    private boolean noRoom;
    // When the dump was last found full and how often it was waited for since it last took something.
    private int dumpFullAt;
    private int dumpWaits;

    public StationStock(Printer printer, Trip trip) {
        this.printer = printer;
        this.trip = trip;
    }

    // Every trip starts with no chest passed over and no wait for a chest counted.
    public void reset() {
        noRoom = false;
        starved = 0;
        outOf.clear();
        fullChests.clear();
    }

    // A dump that stayed full gets its whole count of waits again once the printer is switched on.
    public void switchedOn() {
        dumpWaits = 0;
    }

    // Once a tick. Counts what is carried and lets a chest found without a material come back.
    public void tick() {
        outOf.tick();
        carried = carried();
    }

    // How far a trip gets along the items the cells to come need with what is carried and the
    // slots the printer may fill.
    public MapPicture.Trip<Item> plan(List<Item> items) {
        return MapPicture.plan(items, carried, freeSlots(), Item::getDefaultMaxStackSize);
    }

    // Every material carried by item. The offhand is left out.
    private Map<Item, Integer> carried() {
        Map<Item, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < InventoryUtil.WHOLE_INVENTORY; slot++) {
            ItemStack stack = MC.player.getInventory().getItem(slot);
            if (isMaterial(stack)) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    // A block the picture needs or a marked chest held.
    private boolean isMaterial(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem
            && (printer.pictureItems().contains(stack.getItem()) || printer.station().anyChestHolds(stack.getItem()));
    }

    // Slots the printer may fill. Empty ones and those holding materials.
    private int freeSlots() {
        int free = 0;
        for (int slot = 0; slot < InventoryUtil.WHOLE_INVENTORY; slot++) {
            ItemStack stack = MC.player.getInventory().getItem(slot);
            if (stack.isEmpty() || isMaterial(stack)) {
                free++;
            }
        }
        return free;
    }

    public int carrying(Item item) {
        return carried.getOrDefault(item, 0);
    }

    // Materials the trip does not need are put away when the trip needs their room.
    public boolean dumpLeftovers(Map<Item, Integer> needs) {
        Predicate<Item> unneeded = item -> !needs.containsKey(item);
        if (!carriesAny(unneeded) || roomForTrip(needs)) {
            return false;
        }
        return putAway(unneeded, keepsBlocks());
    }

    // A map that is mined away gives its blocks back and leftovers go home to their chests too. A
    // map washed away is printed from a source that does not run dry and leftovers go into the dump.
    public boolean keepsBlocks() {
        return printer.station().block(Role.RESET) == null;
    }

    // True when the slots that are empty or hold what the trip needs take the whole trip.
    private boolean roomForTrip(Map<Item, Integer> needs) {
        int room = 0;
        for (int slot = 0; slot < InventoryUtil.WHOLE_INVENTORY; slot++) {
            ItemStack stack = MC.player.getInventory().getItem(slot);
            if (stack.isEmpty() || needs.containsKey(stack.getItem())) {
                room++;
            }
        }
        int wanted = 0;
        for (Map.Entry<Item, Integer> need : needs.entrySet()) {
            int stack = need.getKey().getDefaultMaxStackSize();
            wanted += (Math.max(need.getValue(), carrying(need.getKey())) + stack - 1) / stack;
        }
        return wanted <= room;
    }

    private boolean carriesAny(Predicate<Item> which) {
        for (Item item : carried.keySet()) {
            if (which.test(item)) {
                return true;
            }
        }
        return false;
    }

    // Fetches what the trip needs from the chests that hold it. True whilst a visit is under
    // way or the printer waits for a chest to fill. A chest found without a material is looked
    // in again for it after a while and a material that stays out of stock stops the printer. A
    // trip that has some of a material goes as far as that takes it.
    public boolean restock(Map<Item, Integer> needs) {
        if (noRoom) {
            return false;
        }
        for (Map.Entry<Item, Integer> need : needs.entrySet()) {
            Item item = need.getKey();
            if (carrying(item) >= need.getValue()) {
                continue;
            }
            MapStations.Chest chest = nearestChest(item);
            if (chest != null) {
                printer.openVisit(chest.pos(), "the chest of " + Printer.itemName(item), Printer::storage,
                    menu -> take(menu, chest.pos(), item, need.getValue()));
                return true;
            }
            if (carrying(item) > 0) {
                continue;
            }
            if (!printer.station().anyChestHolds(item)) {
                printer.disable("No marked chest holds " + Printer.itemName(item)
                    + ". Mark the station again with one that does.");
            } else if (++starved > EMPTY_TICKS * Printer.TRIES) {
                printer.disable(Printer.NAME + " ran out of " + Printer.itemName(item)
                    + ". Every chest that held it stayed empty.");
            } else {
                trip.stop();
            }
            return true;
        }
        return false;
    }

    // The nearest chest known for the item that was not found without it a moment ago.
    private MapStations.Chest nearestChest(Item item) {
        MapStations.Chest best = null;
        double nearest = Double.MAX_VALUE;
        for (MapStations.Chest chest : printer.station().chests()) {
            if (!chest.holds(item) || outOf.contains(new Out(chest.pos(), item))) {
                continue;
            }
            double distance = chest.pos().distToCenterSqr(MC.player.position());
            if (distance < nearest) {
                nearest = distance;
                best = chest;
            }
        }
        return best;
    }

    // One stack of the item out of the open chest a tick until the trip has enough. A chest
    // with none left is passed over for that item for a while.
    private Step take(AbstractContainerMenu menu, BlockPos chest, Item item, int wanted) {
        learn(chest, menu);
        int held = InventoryUtil.count(item, InventoryUtil.WHOLE_INVENTORY);
        if (held >= wanted) {
            return Step.DONE;
        }
        int from = MenuClicks.firstContainerSlot(menu, stack -> stack.is(item));
        if (from == -1) {
            outOf.put(new Out(chest, item), EMPTY_TICKS);
            return Step.DONE;
        }
        if (MenuClicks.room(menu, MenuClicks.inventorySlots(menu), menu.slots.get(from).getItem()) == 0) {
            noRoom = true;
            return Step.DONE;
        }
        if (!printer.mayClick()) {
            return Step.DONE;
        }
        MenuClicks.quickMove(menu, from);
        starved = 0;
        return Step.MORE;
    }

    // Every kind a chest of materials holds is learnt whenever it is open. A kind taken out to the
    // last stack stays known.
    private void learn(BlockPos pos, AbstractContainerMenu menu) {
        Station station = printer.station();
        if (station.chestAt(pos) != null && station.seen(pos, blocksIn(menu))) {
            MapStations.save();
        }
    }

    // Puts every material the test picks into a chest that holds it or else into the dump.
    // True whilst something is still to go. The dump alone takes it when the chests are not
    // asked for. A chest found full is passed over and the dump takes what is left. Glass panes
    // lock maps and are never put away.
    public boolean putAway(Predicate<Item> picked, boolean toChests) {
        Predicate<Item> which = picked.and(item -> item != Items.GLASS_PANE);
        Item item = null;
        for (Item carriedItem : carried.keySet()) {
            if (which.test(carriedItem)) {
                item = carriedItem;
                break;
            }
        }
        if (item == null) {
            fullChests.clear();
            return false;
        }
        BlockPos dump = printer.station().block(Role.DUMP);
        MapStations.Chest chest = toChests ? chestWithRoom(item) : null;
        BlockPos target = chest != null ? chest.pos() : dump;
        if (target.equals(dump) && fullChests.contains(dump)) {
            // A dump that drains such as a hopper gets time to make room.
            trip.stop();
            if (printer.ticks() - dumpFullAt < EMPTY_TICKS) {
                return true;
            }
            if (++dumpWaits > Printer.TRIES) {
                printer.disable("The dump at " + BlockUtil.text(dump) + " stayed full.");
                return true;
            }
            fullChests.remove(dump);
        }
        Item going = item;
        if (chest != null) {
            printer.openVisit(target, "the chest of " + Printer.itemName(item), Printer::storage,
                menu -> put(menu, target, stack -> stack.is(going)));
        } else {
            printer.openVisit(target, "the dump", Printer::dumpBlock,
                menu -> put(menu, target, stack -> isMaterial(stack) && which.test(stack.getItem())));
        }
        return true;
    }

    private MapStations.Chest chestWithRoom(Item item) {
        for (MapStations.Chest chest : printer.station().chests()) {
            if (chest.holds(item) && !fullChests.contains(chest.pos())) {
                return chest;
            }
        }
        return null;
    }

    // One stack a tick from the inventory into the open container. A stack that stays put has
    // found the container full.
    private Step put(AbstractContainerMenu menu, BlockPos target, Predicate<ItemStack> which) {
        if (printer.station().chestAt(target) != null) {
            learn(target, menu);
        }
        int from = MenuClicks.firstInventorySlot(menu, which);
        if (from == -1 || !printer.mayClick()) {
            return Step.DONE;
        }
        if (!MenuClicks.quickMoved(menu, from)) {
            fullChests.add(target);
            dumpFullAt = printer.ticks();
            return Step.DONE;
        }
        dumpWaits = 0;
        return Step.MORE;
    }

    // The kinds of block a container menu holds.
    public static Set<Item> blocksIn(AbstractContainerMenu menu) {
        Set<Item> items = new LinkedHashSet<>();
        for (int slot : MenuClicks.containerSlots(menu)) {
            ItemStack stack = menu.slots.get(slot).getItem();
            if (stack.getItem() instanceof BlockItem) {
                items.add(stack.getItem());
            }
        }
        return items;
    }
}
