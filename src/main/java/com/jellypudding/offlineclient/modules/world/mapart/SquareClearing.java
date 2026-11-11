package com.jellypudding.offlineclient.modules.world.mapart;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.MapStations.Role;
import com.jellypudding.offlineclient.config.MapStations.Stage;
import com.jellypudding.offlineclient.config.MapStations.Station;
import com.jellypudding.offlineclient.path.MiningTrip;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.StationVisit;
import com.jellypudding.offlineclient.util.StationVisit.Step;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.BitSet;
import java.util.function.Predicate;

// Clears the picture off the map square once its map is stored.
public final class SquareClearing {

    // How the reset of a carpet map goes.
    private enum Reset { PRESS, WASH, RELEASE, DRAIN }

    private static final Minecraft MC = OfflineClient.MC;

    // Ticks between looks over the whole square whilst it is cleared.
    private static final int CHECK_TICKS = 10;
    // Ticks a lever gets to show it moved on top of the round trip.
    private static final int SWITCH_TICKS = 20;
    // The longest the square may go without one more cell washed or drained once the reset is pressed.
    private static final int WASH_TICKS = 1200;
    // Presses of a reset that washed too little before the printer gives up on it.
    private static final int PRESSES = 3;
    // Ticks with nothing more mined before the printer gives up on mining the square away.
    private static final int MINE_STALL_TICKS = 2400;

    private final Printer printer;
    private final StationStock stock;
    private final StationVisit visit;
    private final MiningTrip miner;
    private final InventoryUtil.SlotSwap tools;

    private Reset reset = Reset.PRESS;
    // The cells last seen the way the clearing waits for.
    private final BitSet clearedCells = new BitSet();
    private int mostCleared;
    private int waitTicks;
    // The nearest cell out of view not yet seen passing. Null whilst every such cell is in view.
    private BlockPos unseen;
    // The tick a look over the square last found nothing to mine. Minus one before.
    private int nothingToMineAt = -1;
    // Whether the reset is a lever. Read whenever it is in view and kept for when it is not.
    private boolean leverReset;
    private int presses;
    private int leverTries;
    // The tick the last press of the reset went out. Minus one whilst none is waited on.
    private int pressedAt = -1;
    // Why the printer stops once the lever is off again. Null to carry on.
    private String afterRelease;

    public SquareClearing(Printer printer, StationStock stock, StationVisit visit, MiningTrip miner,
                          InventoryUtil.SlotSwap tools) {
        this.printer = printer;
        this.stock = stock;
        this.visit = visit;
        this.miner = miner;
        this.tools = tools;
    }

    // Every file starts its clearing afresh.
    public void reset() {
        reset = Reset.PRESS;
        clearedCells.clear();
        mostCleared = 0;
        waitTicks = 0;
        unseen = null;
        nothingToMineAt = -1;
        presses = 0;
        leverTries = 0;
        pressedAt = -1;
        afterRelease = null;
    }

    // A marked reset washes the square. Without one the map is mined away and its blocks go
    // back into their chests.
    public void clear() {
        if (printer.visiting()) {
            return;
        }
        if (printer.station().block(Role.RESET) != null) {
            washAway();
        } else {
            mineAway();
        }
    }

    // True whilst a lever keeps the water of a wash running over the square.
    public boolean holdsWater() {
        Station station = printer.station();
        return station.stage() == Stage.CLEAR && station.block(Role.RESET) != null && reset == Reset.WASH
            && leverReset;
    }

    // Switches the lever off and then stops the printer with the reason.
    public void stopAfterRelease(String reason) {
        afterRelease = reason;
        beginWait(Reset.RELEASE);
    }

    // Presses the reset and waits until no block of the picture stands and then until the water
    // has drained. A lever is switched off between the two and before a stop for a wash that
    // did too little.
    private void washAway() {
        BlockPos pos = printer.station().block(Role.RESET);
        BlockState there = BlockUtil.state(pos);
        // The reset is out of view whilst the walk looks over a far part of the square.
        boolean seen = !there.is(Blocks.VOID_AIR);
        if (seen) {
            leverReset = there.getBlock() instanceof LeverBlock;
        }
        switch (reset) {
            case PRESS -> {
                if (seen) {
                    pressReset(pos, there, leverReset);
                } else {
                    printer.goTo(pos);
                }
            }
            case WASH -> {
                if (waitedFor(SquareClearing::washed)) {
                    beginWait(leverReset ? Reset.RELEASE : Reset.DRAIN);
                } else if (stalled()) {
                    String standing = "The reset left blocks of " + printer.station().file()
                        + " standing on the map square.";
                    if (leverReset || presses >= PRESSES) {
                        printer.halt(standing);
                    } else {
                        reset = Reset.PRESS;
                    }
                }
            }
            case RELEASE -> {
                if (seen) {
                    releaseLever(pos, there);
                } else {
                    printer.goTo(pos);
                }
            }
            case DRAIN -> {
                if (waitedFor(SquareClearing::dry)) {
                    printer.cleared();
                } else if (stalled()) {
                    printer.disable("Water still stands on the map square after the reset.");
                }
            }
        }
    }

    // A lever on already has nothing to press. Otherwise the wash is waited for once the press
    // went out and a lever shows it moved. A press cut short is made again.
    private void pressReset(BlockPos pos, BlockState there, boolean lever) {
        if (lever && powered(there)) {
            leverTries = 0;
            startWash();
            return;
        }
        if (pressedAt < 0) {
            if (there.getBlock() instanceof TrappedChestBlock) {
                waitTicks = 0;
                printer.openVisit(pos, "the reset", Printer::resetBlock, this::holdOpen);
                printer.afterVisit(this::startWash);
            } else {
                visit.press(pos, "the reset", Printer::resetBlock);
                printer.afterVisit(() -> pressedAt = printer.ticks());
            }
            return;
        }
        if (!lever) {
            startWash();
        } else if (printer.ticks() - pressedAt > ServerInfo.answerTicks() + SWITCH_TICKS) {
            pressedAt = -1;
            if (++leverTries >= Printer.TRIES) {
                printer.disable("The lever at " + BlockUtil.text(pos) + " did not switch on.");
            }
        }
    }

    // The trapped chest powers its redstone whilst it stays open. Each opening is held until no
    // block in view is left or nothing more washes for a while.
    private Step holdOpen(AbstractContainerMenu menu) {
        if (printer.ticks() % CHECK_TICKS == 0 && checkCells(SquareClearing::washed) || ++waitTicks > WASH_TICKS) {
            return Step.DONE;
        }
        return Step.MORE;
    }

    private void startWash() {
        reset = Reset.WASH;
        presses++;
        pressedAt = -1;
        waitTicks = 0;
    }

    // Switches the lever off. The drain is waited for once it shows it moved.
    private void releaseLever(BlockPos pos, BlockState there) {
        if (!powered(there)) {
            leverTries = 0;
            pressedAt = -1;
            if (afterRelease != null) {
                printer.disable(afterRelease);
            } else {
                beginWait(Reset.DRAIN);
            }
            return;
        }
        if (pressedAt < 0) {
            visit.press(pos, "the reset", Printer::resetBlock);
            printer.afterVisit(() -> pressedAt = printer.ticks());
        } else if (printer.ticks() - pressedAt > ServerInfo.answerTicks() + SWITCH_TICKS) {
            pressedAt = -1;
            if (++leverTries >= Printer.TRIES) {
                printer.disable("The lever at " + BlockUtil.text(pos) + " did not switch off.");
            }
        }
    }

    private void beginWait(Reset next) {
        reset = next;
        clearedCells.clear();
        mostCleared = 0;
        waitTicks = 0;
        pressedAt = -1;
        unseen = null;
    }

    private static boolean powered(BlockState state) {
        return state.getOptionalValue(BlockStateProperties.POWERED).orElse(false);
    }

    // No block of the picture stands in the cell. Water may still run over it.
    private static boolean washed(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        return state.isAir() || state.getBlock() instanceof LiquidBlock;
    }

    // The cell is empty air.
    private static boolean dry(BlockPos pos) {
        return BlockUtil.state(pos).isAir();
    }

    // Marks which cells the picture covers pass the test. A cell out of view keeps its mark. More
    // cells passing than ever before sets the wait back. True when no cell in view fails.
    private boolean checkCells(Predicate<BlockPos> done) {
        boolean inView = true;
        for (int cell : printer.covered()) {
            BlockPos pos = printer.cellPos(cell);
            if (BlockUtil.state(pos).is(Blocks.VOID_AIR)) {
                continue;
            }
            boolean passes = done.test(pos);
            clearedCells.set(cell, passes);
            inView &= passes;
        }
        int count = clearedCells.cardinality();
        if (count > mostCleared) {
            mostCleared = count;
            waitTicks = 0;
        }
        return inView;
    }

    private boolean allCleared() {
        return clearedCells.cardinality() == printer.covered().length;
    }

    // Looks over the square every few ticks. True once every cell passes.
    private boolean waitedFor(Predicate<BlockPos> done) {
        if (printer.ticks() % CHECK_TICKS == 0) {
            checkCells(done);
            unseen = nearestUnseen();
        }
        if (allCleared()) {
            printer.arrived();
            return true;
        }
        return false;
    }

    // Walks on towards the part out of view and counts the tick. True once the square has gone
    // too long without one more cell washed or drained. A walk that stopped the printer counts
    // nothing.
    private boolean stalled() {
        return walkToUnseen() != Printer.Walk.STOPPED && ++waitTicks > WASH_TICKS;
    }

    // Walks towards the part out of view not yet seen passing. A short view never sees the whole
    // square at once. With every such part in view the player stands.
    private Printer.Walk walkToUnseen() {
        if (unseen == null) {
            printer.arrived();
            return Printer.Walk.THERE;
        }
        return printer.goTo(unseen);
    }

    // The nearest cell out of view not yet seen passing. Null when every such cell is in view.
    private BlockPos nearestUnseen() {
        int nearest = printer.nearestCell(IntArrayList.wrap(printer.covered()),
            (cell, pos) -> !clearedCells.get(cell) && BlockUtil.state(pos).is(Blocks.VOID_AIR));
        return nearest == -1 ? null : printer.cellPos(nearest);
    }

    // Mines the square one block at a time and picks up what falls. A full inventory empties
    // into the chests that hold each block and the rest into the dump. Whatever lies out of view
    // is walked to once nothing in view is left to mine.
    private void mineAway() {
        boolean full = MC.player.getInventory().getFreeSlot() == -1;
        if (full && stock.putAway(item -> true, true)) {
            miner.stop();
            return;
        }
        BlockPos target = miner.target();
        if (target != null) {
            int slot = Modules.toolSlot(BlockUtil.state(target));
            if (slot != -1) {
                tools.select(slot);
            }
        }
        boolean mining = miner.tick(this::toMine, this::nextToMine, printer.faceTarget() != FaceMode.OFF, true);
        if (printer.ticks() % CHECK_TICKS == 0) {
            checkCells(pos -> !toMine(pos));
            unseen = nearestUnseen();
        }
        if (++waitTicks > MINE_STALL_TICKS) {
            printer.disable(Printer.NAME + " could not mine the map square clear." + firstStanding());
            return;
        }
        if (mining) {
            printer.arrived();
            return;
        }
        if (!allCleared()) {
            walkToUnseen();
            return;
        }
        miner.stop();
        tools.restoreIfMine();
        if (!stock.putAway(item -> true, true)) {
            printer.cleared();
        }
    }

    // A block standing in a cell of the square the picture covers that can be mined.
    private boolean toMine(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        return !state.isAir() && state.getFluidState().isEmpty() && BlockUtil.diggable(pos);
    }

    // The first such block in walk order that the miner has not left alone for a while. Null for
    // none. A look that found none is not made again for a few ticks.
    private BlockPos nextToMine() {
        if (nothingToMineAt >= 0 && printer.ticks() - nothingToMineAt < CHECK_TICKS) {
            return null;
        }
        int first = printer.firstCell(IntArrayList.wrap(printer.covered()),
            (cell, pos) -> toMine(pos) && !miner.shuns(pos));
        if (first == -1) {
            nothingToMineAt = printer.ticks();
            return null;
        }
        return printer.cellPos(first);
    }

    private String firstStanding() {
        int first = printer.firstCell(IntArrayList.wrap(printer.covered()), (cell, pos) -> toMine(pos));
        return first == -1 ? "" : " A block is left at " + BlockUtil.text(printer.cellPos(first)) + ".";
    }
}
