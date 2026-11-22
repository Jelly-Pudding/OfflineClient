package com.jellypudding.offlineclient.modules.world.mapart;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.MapFiles;
import com.jellypudding.offlineclient.config.MapStations;
import com.jellypudding.offlineclient.config.MapStations.Role;
import com.jellypudding.offlineclient.config.MapStations.Station;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;

// Marks the blocks of a station one right click at a time and reads each chest of materials once
// its screen opens.
public final class StationMarking {

    private static final Minecraft MC = OfflineClient.MC;

    private static final int OPEN_TICKS = 40;

    private final Printer printer;
    private Role next;

    private BlockPos opening;
    private int openingMenu;
    private int openingTicks;

    public StationMarking(Printer printer, Role next) {
        this.printer = printer;
        this.next = next;
    }

    public static Role nextRole(Station station) {
        for (Role role : List.of(Role.TABLE, Role.MAPS, Role.FINISHED, Role.DUMP)) {
            if (station.block(role) == null) {
                return role;
            }
        }
        return station.chests().isEmpty() && station.block(Role.RESET) == null ? Role.RESET : Role.CHESTS;
    }

    public void prompt() {
        ChatUtil.message(Printer.TAG + switch (next) {
            case TABLE -> "Right click the cartography table.";
            case MAPS -> "Right click the chest with empty maps and glass panes.";
            case FINISHED -> "Right click the chest the finished maps go into.";
            case DUMP -> "Right click the chest or hopper that leftovers go into.";
            case RESET -> "Right click the button or lever or trapped chest that washes the map away. Right click"
                + " the cartography table if there is none and the map is mined away instead.";
            case CHESTS -> "Right click every chest of materials. Each one opens for the printer to read what it"
                + " holds. Right click the cartography table once every one is marked.";
        });
    }

    // A chest of materials not marked yet opens as usual whilst the chests are marked.
    public boolean opensChest(BlockPos pos, BlockState state) {
        return next == Role.CHESTS && Printer.storage(state) && roleOf(pos, state) == null && !inPicture(pos, state);
    }

    public void awaitChest(BlockPos pos) {
        opening = pos;
        openingMenu = MC.player.containerMenu.containerId;
        openingTicks = 0;
    }

    // True once the marking is over.
    public boolean mark(BlockPos pos, BlockState state) {
        // The fix of mistakes would break whatever stands where the picture goes.
        if (inPicture(pos, state)) {
            ChatUtil.error("That block stands where the picture goes.");
            return false;
        }
        Station station = printer.station();
        boolean table = state.is(Blocks.CARTOGRAPHY_TABLE);
        Role taken = roleOf(pos, state);
        switch (next) {
            case TABLE -> {
                if (!table) {
                    ChatUtil.error("That is no cartography table.");
                    return false;
                }
                station.mark(Role.TABLE, pos);
            }
            case MAPS, FINISHED -> {
                if (!Printer.storage(state) || taken != null) {
                    ChatUtil.error(taken != null ? "That block is marked already." : "That is no chest or barrel.");
                    return false;
                }
                station.mark(next, pos);
            }
            case DUMP -> {
                if (!Printer.storage(state) && !(state.getBlock() instanceof HopperBlock) || taken != null) {
                    ChatUtil.error(taken != null ? "That block is marked already." : "That is no chest or hopper.");
                    return false;
                }
                station.mark(Role.DUMP, pos);
            }
            case RESET -> {
                if (!table && (!Printer.resetBlock(state) || taken != null)) {
                    ChatUtil.error(taken != null ? "That block is marked already."
                        : "That is no button or lever or trapped chest.");
                    return false;
                }
                station.mark(Role.RESET, table ? null : pos);
            }
            case CHESTS -> {
                if (table) {
                    return finishMarking();
                }
                if (station.unmark(pos) || station.unmark(BlockUtil.otherChestHalf(pos, state))) {
                    ChatUtil.message(Printer.TAG + "no longer takes from that chest.");
                    MapStations.save();
                    return false;
                }
                ChatUtil.error(taken != null ? "That block is marked already." : "That is no chest or barrel.");
                return false;
            }
        }
        MapStations.save();
        next = next == Role.RESET ? Role.CHESTS : nextRole(station);
        prompt();
        return false;
    }

    private boolean finishMarking() {
        if (opening != null) {
            ChatUtil.error("Wait until the chest you clicked opens.");
            return false;
        }
        Station station = printer.station();
        if (station.chests().isEmpty()) {
            ChatUtil.error("Mark at least one chest of materials first.");
            return false;
        }
        station.setMarked(true);
        MapStations.save();
        ChatUtil.component(Component.literal(Printer.TAG + "is set up. It prints the files in ")
            .append(ChatUtil.folderLink(MapFiles.folder())).append("§7 from A to Z."));
        return true;
    }

    private Role roleOf(BlockPos pos, BlockState state) {
        Station station = printer.station();
        BlockPos other = BlockUtil.otherChestHalf(pos, state);
        for (Role role : Role.values()) {
            BlockPos marked = station.block(role);
            if (marked != null && (marked.equals(pos) || marked.equals(other))) {
                return role;
            }
        }
        return station.chestAt(pos) != null || station.chestAt(other) != null ? Role.CHESTS : null;
    }

    private boolean inPicture(BlockPos pos, BlockState state) {
        BlockPos other = BlockUtil.otherChestHalf(pos, state);
        return printer.footprint().isInside(pos) || other != null && printer.footprint().isInside(other);
    }

    public void readOpenedChest() {
        if (opening == null) {
            return;
        }
        AbstractContainerMenu menu = InventoryUtil.openedSince(openingMenu);
        if (menu == null) {
            if (++openingTicks > OPEN_TICKS) {
                opening = null;
                ChatUtil.error("That chest would not open.");
            }
            return;
        }
        Set<Item> items = StationStock.blocksIn(menu);
        BlockPos pos = opening;
        opening = null;
        if (items.isEmpty()) {
            ChatUtil.error("That chest holds no blocks. Put its material in and right click it again.");
            return;
        }
        printer.station().seen(pos, items);
        MapStations.save();
        ChatUtil.message(Printer.TAG + "marked a chest of §f" + Printer.named(items) + "§7.");
    }
}
