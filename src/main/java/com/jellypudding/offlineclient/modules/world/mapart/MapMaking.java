package com.jellypudding.offlineclient.modules.world.mapart;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.MapStations;
import com.jellypudding.offlineclient.config.MapStations.Role;
import com.jellypudding.offlineclient.config.MapStations.Stage;
import com.jellypudding.offlineclient.config.MapStations.Station;
import com.jellypudding.offlineclient.path.Trip;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.MapPicture;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.StationVisit.Step;
import com.jellypudding.offlineclient.util.Tally;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Makes the map of a printed picture. An empty map is used in the middle of the map square and
// held until it shows the whole picture. It is then locked at the cartography table and stored
// in the chest for finished maps.
public final class MapMaking {

    private static final Minecraft MC = OfflineClient.MC;
    private static final int SIDE = MapPicture.SIDE;

    // Ticks the empty map gets to turn into a map.
    private static final int MAP_TICKS = 40;
    // Ticks with no pixel filled before the walk moves towards the blank part. Sixteen ticks
    // draw every column of a map once.
    private static final int STILL_TICKS = 60;
    // The longest a map may take to fill.
    private static final int FILL_TICKS = 6000;
    // Ticks the cartography table gets to hand over the locked map.
    private static final int LOCK_TICKS = 60;

    private final Printer printer;
    private final StationStock stock;
    private final Trip trip;
    private final InventoryUtil.HotbarLoan loan;

    private int mapWait;
    private int mapTries;
    private int mapsLost;
    private Set<Integer> mapsBefore = Set.of();
    private int lastBlank = Integer.MAX_VALUE;
    private int stillTicks;
    private int fillTicks;
    private BlockPos fillSpot;
    private int lockWait;
    private boolean resultTaken;
    // Ticks since the locked map was shift clicked into the chest. Minus one before.
    private int storeWait = -1;

    public MapMaking(Printer printer, StationStock stock, Trip trip, InventoryUtil.HotbarLoan loan) {
        this.printer = printer;
        this.stock = stock;
        this.trip = trip;
        this.loan = loan;
    }

    // Every file starts its map afresh.
    public void reset() {
        mapWait = 0;
        mapTries = 0;
        mapsLost = 0;
        resetFill();
        lockWait = 0;
        resultTaken = false;
        storeWait = -1;
    }

    public void makeMap() {
        if (printer.visiting()) {
            return;
        }
        int filled = slotOfMap(printer.station().map());
        if (filled != -1) {
            fillMap(filled);
            return;
        }
        if (stock.putAway(item -> true, stock.keepsBlocks())) {
            return;
        }
        if (fetchMapItems(List.of(Items.MAP, Items.GLASS_PANE))) {
            return;
        }
        if (printer.goTo(middle()) != Printer.Walk.THERE) {
            return;
        }
        useEmptyMap();
    }

    // The middle of the map square. A map made there shows the square.
    private BlockPos middle() {
        return printer.station().corner().offset(SIDE / 2, 0, SIDE / 2);
    }

    // Fetches one of each item that none is carried of from the chest of empty maps. True whilst
    // something is still to fetch.
    private boolean fetchMapItems(List<Item> wanted) {
        if (wanted.stream().allMatch(item -> InventoryUtil.count(item, InventoryUtil.WHOLE_INVENTORY) > 0)) {
            return false;
        }
        printer.openVisit(printer.station().block(Role.MAPS), "the chest of empty maps", Printer::storage, menu -> {
            for (Item item : wanted) {
                if (InventoryUtil.count(item, InventoryUtil.WHOLE_INVENTORY) > 0) {
                    continue;
                }
                int from = MenuClicks.firstContainerSlot(menu, stack -> stack.is(item));
                if (from == -1) {
                    printer.disable("The chest of empty maps has no "
                        + (item == Items.MAP ? "empty maps" : "glass panes") + " left.");
                    return Step.DONE;
                }
                if (!printer.mayClick()) {
                    return Step.DONE;
                }
                MenuClicks.move(menu, from, MenuClicks.inventorySlots(menu), 1);
                return Step.MORE;
            }
            return Step.DONE;
        });
        return true;
    }

    // Holds the empty map and uses it. The server swaps it for a map of the square.
    private void useEmptyMap() {
        if (mapWait > 0) {
            Integer made = newMap(mapsBefore);
            if (made != null) {
                printer.station().setMap(made);
                MapStations.save();
                mapWait = 0;
                resetFill();
                return;
            }
            if (++mapWait > MAP_TICKS + ServerInfo.pingTicks()) {
                mapWait = 0;
                if (++mapTries >= Printer.TRIES) {
                    printer.disable("The empty map would not turn into a map.");
                }
            }
            return;
        }
        if (!loan.select(InventoryUtil.findSlot(Items.MAP, InventoryUtil.WHOLE_INVENTORY))
            || !MC.player.getMainHandItem().is(Items.MAP)) {
            return;
        }
        mapsBefore = mapIds();
        InputUtil.useMainHand();
        mapWait = 1;
    }

    // A map just made gets the whole of its time to fill.
    private void resetFill() {
        lastBlank = Integer.MAX_VALUE;
        stillTicks = 0;
        fillTicks = 0;
        fillSpot = null;
    }

    // Holds the map until every pixel the picture covers shows something. With nothing new for
    // a while the walk heads for the blank part. The server only draws what it has loaded.
    private void fillMap(int slot) {
        if (!loan.select(slot)) {
            return;
        }
        MapItemSavedData data = MC.level.getMapData(new MapId(printer.station().map()));
        int blank = blankPixels(data);
        if (blank == 0) {
            loan.giveBack();
            trip.stop();
            printer.station().reach(Stage.LOCK);
            MapStations.save();
            return;
        }
        if (blank < lastBlank) {
            lastBlank = blank;
            stillTicks = 0;
        } else if (++stillTicks > STILL_TICKS) {
            fillSpot = nearestBlank(data);
            stillTicks = 0;
        }
        if (printer.goTo(fillSpot != null ? fillSpot : middle()) == Printer.Walk.STOPPED) {
            return;
        }
        if (++fillTicks > FILL_TICKS) {
            printer.disable("The map of " + printer.station().file() + " would not fill. "
                + Tally.counted(blank, "pixel") + " of it stayed blank.");
        }
    }

    // Pixels of the square the picture covers that show nothing yet.
    private int blankPixels(MapItemSavedData data) {
        int blank = 0;
        for (int cell : printer.covered()) {
            if (blank(data, cell)) {
                blank++;
            }
        }
        return blank;
    }

    // A map stores a colour a pixel with its x running fastest. The lowest two bits are the shade.
    private boolean blank(MapItemSavedData data, int cell) {
        if (data == null) {
            return true;
        }
        int z = cell / SIDE + printer.picture().firstRow();
        return Byte.toUnsignedInt(data.colors[z * SIDE + cell % SIDE]) >> 2 == 0;
    }

    private BlockPos nearestBlank(MapItemSavedData data) {
        int nearest = printer.nearestCell(IntArrayList.wrap(printer.covered()), (cell, pos) -> blank(data, cell));
        return nearest == -1 ? null : printer.cellPos(nearest);
    }

    // The inventory slot of the map with that id. Minus one when none is carried.
    private int slotOfMap(int id) {
        if (id == MapStations.NO_MAP) {
            return -1;
        }
        MapId wanted = new MapId(id);
        return InventoryUtil.findSlot(stack -> wanted.equals(stack.get(DataComponents.MAP_ID)),
            InventoryUtil.WHOLE_INVENTORY);
    }

    // The ids of every map carried.
    private Set<Integer> mapIds() {
        Set<Integer> ids = new HashSet<>();
        for (int slot = 0; slot < InventoryUtil.WHOLE_INVENTORY; slot++) {
            MapId id = MC.player.getInventory().getItem(slot).get(DataComponents.MAP_ID);
            if (id != null) {
                ids.add(id.id());
            }
        }
        return ids;
    }

    // A map carried that was not carried before and is not the one being made. Null for none.
    private Integer newMap(Set<Integer> before) {
        for (int id : mapIds()) {
            if (!before.contains(id) && id != printer.station().map()) {
                return id;
            }
        }
        return null;
    }

    // A map that went missing is made again from a fresh empty map. A few lost of one file stop
    // the printer.
    private void remakeMap(String why) {
        Station station = printer.station();
        if (++mapsLost >= Printer.TRIES) {
            printer.disable(why + " " + Printer.NAME + " stopped after losing " + Tally.counted(mapsLost, "map")
                + " of " + station.file() + ".");
            return;
        }
        ChatUtil.error(why + " " + Printer.NAME + " makes another.");
        station.setMap(MapStations.NO_MAP);
        station.setLocked(MapStations.NO_MAP);
        station.reach(Stage.MAP);
        MapStations.save();
        resetFill();
    }

    // Locks the map at the cartography table with a glass pane. The server sets the result and
    // a locked copy with an id of its own comes out. A copy taken just before the printer was
    // let go of or cut off is found by the maps carried before it was taken.
    public void lock() {
        if (printer.visiting()) {
            return;
        }
        Station station = printer.station();
        if (slotOfMap(station.locked()) != -1) {
            station.reach(Stage.STORE);
            MapStations.save();
            return;
        }
        Integer copy = station.mapsBefore().isEmpty() ? null : newMap(station.mapsBefore());
        if (copy != null) {
            station.setLocked(copy);
            station.reach(Stage.STORE);
            MapStations.save();
            return;
        }
        if (slotOfMap(station.map()) == -1) {
            remakeMap("The map of " + station.file() + " is gone.");
            return;
        }
        if (fetchMapItems(List.of(Items.GLASS_PANE))) {
            return;
        }
        lockWait = 0;
        resultTaken = false;
        printer.openVisit(station.block(Role.TABLE), "the cartography table",
            state -> state.is(Blocks.CARTOGRAPHY_TABLE), this::lockAt);
    }

    private Step lockAt(AbstractContainerMenu menu) {
        if (!(menu instanceof CartographyTableMenu table)) {
            printer.disable("The cartography table opened something else.");
            return Step.DONE;
        }
        Station station = printer.station();
        if (resultTaken) {
            Integer locked = newMap(station.mapsBefore());
            if (locked != null) {
                station.setLocked(locked);
                station.reach(Stage.STORE);
                MapStations.save();
                return Step.DONE;
            }
            return waitForTable();
        }
        Slot map = table.getSlot(CartographyTableMenu.MAP_SLOT);
        if (map.getItem().isEmpty()) {
            int from = MenuClicks.slotOf(table, slotOfMap(station.map()));
            if (from == -1 || !printer.mayClick()) {
                return Step.DONE;
            }
            MenuClicks.quickMove(table, from);
            return Step.MORE;
        }
        if (table.getSlot(CartographyTableMenu.ADDITIONAL_SLOT).getItem().isEmpty()) {
            int from = MenuClicks.firstInventorySlot(table, stack -> stack.is(Items.GLASS_PANE));
            if (from == -1 || !printer.mayClick()) {
                return Step.DONE;
            }
            MenuClicks.quickMove(table, from);
            return Step.MORE;
        }
        if (table.getSlot(CartographyTableMenu.RESULT_SLOT).getItem().isEmpty()) {
            return waitForTable();
        }
        if (!printer.mayClick()) {
            return Step.DONE;
        }
        station.setMapsBefore(mapIds());
        MapStations.save();
        MenuClicks.quickMove(table, CartographyTableMenu.RESULT_SLOT);
        resultTaken = true;
        lockWait = 0;
        return Step.MORE;
    }

    private Step waitForTable() {
        if (++lockWait > LOCK_TICKS + ServerInfo.pingTicks()) {
            printer.disable("The cartography table did not lock the map of " + printer.station().file() + ".");
            return Step.DONE;
        }
        return Step.MORE;
    }

    // Puts the locked map into the chest for finished maps.
    public void store() {
        if (printer.visiting()) {
            return;
        }
        Station station = printer.station();
        if (station.locked() == MapStations.NO_MAP) {
            remakeMap("The locked map of " + station.file() + " is gone.");
            return;
        }
        storeWait = -1;
        printer.openVisit(station.block(Role.FINISHED), "the chest for finished maps", Printer::storage, this::storeAt);
    }

    // The map counts as stored once it has left the inventory after the server's answer to the
    // shift click or when the chest shows it already. One neither carried nor in the chest is
    // gone and another is made. A move thrown back makes the visit fail and it is made again.
    private Step storeAt(AbstractContainerMenu menu) {
        Station station = printer.station();
        int from = MenuClicks.slotOf(menu, slotOfMap(station.locked()));
        if (storeWait >= 0) {
            if (++storeWait <= ServerInfo.answerTicks()) {
                return Step.MORE;
            }
            if (from == -1) {
                stored();
            }
            return Step.DONE;
        }
        if (from != -1) {
            if (!printer.mayClick()) {
                return Step.DONE;
            }
            if (!MenuClicks.quickMoved(menu, from)) {
                printer.disable("The chest for finished maps is full.");
                return Step.DONE;
            }
            storeWait = 0;
            return Step.MORE;
        }
        MapId locked = new MapId(station.locked());
        if (MenuClicks.firstContainerSlot(menu, stack -> locked.equals(stack.get(DataComponents.MAP_ID))) != -1) {
            stored();
        } else {
            remakeMap("The locked map of " + station.file() + " is gone.");
        }
        return Step.DONE;
    }

    private void stored() {
        ChatUtil.message(Printer.TAG + "stored the map of §f" + printer.station().file() + "§7.");
        printer.station().reach(Stage.CLEAR);
        MapStations.save();
    }
}
