package com.jellypudding.offlineclient.modules.world.mapart;

import com.jellypudding.offlineclient.config.MapStations.Station;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.MapPicture;
import com.jellypudding.offlineclient.util.StationVisit;
import com.jellypudding.offlineclient.util.Tally;
import it.unimi.dsi.fastutil.ints.IntIterable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Set;
import java.util.function.Predicate;

// MapPrinter as its parts see it. It holds the station and the picture laid on its map square
// and makes every walk and visit with one count of failures in a row.
public interface Printer {

    String NAME = "MapPrinter";
    String TAG = ChatUtil.from(NAME, "");
    // Failed visits or walks in a row before the printer stops. The same goes for maps of one
    // file that went missing and for presses a lever did not show.
    int TRIES = 3;

    @FunctionalInterface
    interface CellTest {
        boolean test(int cell, BlockPos pos);
    }

    enum Walk {
        // There or as near as the walk gets.
        THERE,
        // Not there yet. A walk that failed starts afresh.
        ON_WAY,
        // The walk kept failing and stopped the printer. Nothing more of the tick may run.
        STOPPED
    }

    Station station();

    MapPicture picture();

    // The cells of the map square the picture covers in walk order. The map draws these.
    int[] covered();

    Set<Item> pictureItems();

    BlockPos cellPos(int cell);

    // The cell nearest the player that passes the test. Minus one when none does. Of two as near
    // the first wins.
    int nearestCell(IntIterable cells, CellTest test);

    // Minus one when none passes.
    int firstCell(IntIterable cells, CellTest test);

    // Where a picture goes. The map square and the row north of it at the height of the picture.
    BoundingBox footprint();

    int ticks();

    FaceMode faceTarget();

    // One tick of the visit under way. A visit that failed is made again a few times and then
    // the printer stops with its reason. True whilst it is under way and once the printer has
    // stopped. Nothing more of the tick may run then.
    boolean visiting();

    void openVisit(BlockPos pos, String name, Predicate<BlockState> fits, StationVisit.Work work);

    void afterVisit(Runnable then);

    // Counts a click of the visit under way. False once the visit has made as many as a double
    // chest has slots and more. A server that keeps throwing a move back ends the visit that way.
    boolean mayClick();

    Walk goTo(BlockPos spot);

    void arrived();

    // Stops with the reason. A lever that washes the square would keep the water running and is
    // switched off first.
    void halt(String reason);

    // The file moves into the finished folder only once its picture is gone. One that cannot be
    // moved would be printed again and stops the printer.
    void cleared();

    void disable(String reason);

    static boolean storage(BlockState state) {
        Block block = state.getBlock();
        return block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock;
    }

    static boolean dumpBlock(BlockState state) {
        return storage(state) || state.getBlock() instanceof HopperBlock;
    }

    static boolean resetBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof ButtonBlock || block instanceof LeverBlock || block instanceof TrappedChestBlock;
    }

    static String itemName(Item item) {
        return item.getName(new ItemStack(item)).getString();
    }

    static String named(Set<Item> items) {
        return Tally.firstFew(items.stream().map(Printer::itemName).toList());
    }
}
