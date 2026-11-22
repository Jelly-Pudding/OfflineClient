package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.path.PathGoal;
import com.jellypudding.offlineclient.path.Trip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// The player walks up to the block and clicks it. A block that opens a menu hands the open menu to
// the caller a tick at a time until the caller is done. The menu stays open until the server has
// answered the last click. Every wait has a time limit and a visit that cannot go on says why in a
// whole sentence.
public final class StationVisit implements WorkedScreens.Worker {

    public enum State { WALKING, OPENING, OPEN, DONE, FAILED }

    public enum Step { MORE, DONE }

    @FunctionalInterface
    public interface Work {
        Step work(AbstractContainerMenu menu);
    }

    private static final Minecraft MC = OfflineClient.MC;

    // A walk ends this close to the block. The eyes reach it from anywhere that near.
    private static final double BESIDE = 2;
    private static final int OPEN_TICKS = 20;
    private static final int TRIES = 3;
    // Ticks one visit may keep a menu open. Each caller keeps time for its own work and this
    // only ends a visit whose caller never says it is done.
    private static final int MOST_OPEN_TICKS = 3600;
    private static final int KEPT_TICKS = 200;

    private final Trip trip;
    // The name of whoever makes the visit. Sentences that say why it failed start with it.
    private final String owner;
    private BlockPos target;
    private String name;
    private Predicate<BlockState> fits;
    // Null for a block that is only pressed such as a button.
    private Work work;
    private State state = State.DONE;
    private int clickedMenu;
    // The menu a click opens never shows in another level. Held weakly to keep no level left behind
    // alive.
    private WeakReference<ClientLevel> clickedIn = new WeakReference<>(null);
    private int openedMenu;
    private List<ItemStack> opened = List.of();
    private boolean moved;
    private int settled = -1;
    // Whether the click of this try has gone out. A container ChestLink keeps can hold it back.
    private boolean clicked;
    private int lateTicks;
    private int waited;
    private int tries;
    private String failure;

    public StationVisit(Trip trip, String owner) {
        this.trip = trip;
        this.owner = owner;
        WorkedScreens.add(this);
    }

    // The name reads like the dump chest and the test says what the block has to be.
    public void open(BlockPos pos, String name, Predicate<BlockState> fits, Work work) {
        begin(pos, name, fits, work);
    }

    public void press(BlockPos pos, String name, Predicate<BlockState> fits) {
        begin(pos, name, fits, null);
    }

    private void begin(BlockPos pos, String name, Predicate<BlockState> fits, Work work) {
        stop();
        this.target = pos.immutable();
        this.name = name;
        this.fits = fits;
        this.work = work;
        state = State.WALKING;
        waited = 0;
        tries = 0;
        failure = null;
    }

    public boolean busy() {
        return state == State.WALKING || state == State.OPENING || state == State.OPEN;
    }

    public String failure() {
        return failure;
    }

    public State tick(FaceMode face) {
        switch (state) {
            case WALKING -> walk(face);
            case OPENING -> waitForMenu(face);
            case OPEN -> workMenu();
            default -> {
            }
        }
        return state;
    }

    private void walk(FaceMode face) {
        BlockState there = BlockUtil.state(target);
        // A chunk that is not loaded yet reads as void air. The walk goes on until it loads.
        if (!there.is(Blocks.VOID_AIR) && !fits.test(there)) {
            fail(ChatUtil.capitalised(name) + " at " + BlockUtil.text(target) + " is gone.");
            return;
        }
        if (fits.test(there) && BlockUtil.reaches(target, BlockUtil.serverBlockReach())) {
            trip.stop();
            state = State.OPENING;
            waited = 0;
            clicked = false;
            click(face);
            return;
        }
        if (!trip.active() && !trip.start(new PathGoal.Around(target, BESIDE))) {
            return;
        }
        Trip.State walked = trip.tick();
        if (walked == Trip.State.FAILED) {
            fail(owner + " could not find a way to " + name + ".");
        } else if (walked == Trip.State.ARRIVED && !BlockUtil.reaches(target, BlockUtil.serverBlockReach())) {
            fail(owner + " could not get near enough to " + name + ".");
        }
    }

    // A container ChestLink keeps open would close as another one opens. The visit waits for it.
    private void click(FaceMode face) {
        if (InventoryUtil.menuKept()) {
            if (++waited > KEPT_TICKS) {
                fail("ChestLink keeps a container open and " + name + " cannot be opened beside it.");
            }
            return;
        }
        clickedMenu = MC.player.containerMenu.containerId;
        clickedIn = new WeakReference<>(MC.level);
        waited = 0;
        tries++;
        clicked = true;
        boolean taken = BlockUtil.interact(target, face);
        if (work != null) {
            return;
        }
        if (taken) {
            state = State.DONE;
        } else if (tries >= TRIES) {
            fail(ChatUtil.capitalised(name) + " at " + BlockUtil.text(target) + " did not take the click.");
        } else {
            clicked = false;
        }
    }

    private void waitForMenu(FaceMode face) {
        if (!clicked) {
            click(face);
            return;
        }
        AbstractContainerMenu menu = InventoryUtil.openedSince(clickedMenu);
        if (menu != null) {
            openedMenu = menu.containerId;
            state = State.OPEN;
            waited = 0;
            opened = inventory();
            moved = false;
            settled = -1;
            return;
        }
        if (++waited <= OPEN_TICKS + ServerInfo.pingTicks()) {
            return;
        }
        if (tries >= TRIES) {
            fail(ChatUtil.capitalised(name) + " at " + BlockUtil.text(target) + " would not open.");
        } else {
            clicked = false;
            click(face);
        }
    }

    // A click the server turns down comes back in a packet for this menu. A menu closed before it
    // comes leaves the client showing the move as made.
    private void workMenu() {
        AbstractContainerMenu live = MC.player.containerMenu;
        if (live.containerId != openedMenu || !InventoryUtil.containerOpen()) {
            fail(ChatUtil.capitalised(name) + " closed before " + owner + " was done with it.");
            return;
        }
        if (settled >= 0) {
            if (++settled < ServerInfo.answerTicks()) {
                return;
            }
            if (moved && ItemUtil.sameStacks(opened, inventory())) {
                fail("The server keeps throwing back what " + owner + " moves at " + name + ".");
                return;
            }
            close();
            state = State.DONE;
            return;
        }
        if (++waited > MOST_OPEN_TICKS) {
            fail(owner + " took too long at " + name + ".");
            return;
        }
        List<ItemStack> before = inventory();
        Step step = work.work(live);
        // The caller may have stopped the visit or switched itself off.
        if (state != State.OPEN) {
            return;
        }
        moved |= !ItemUtil.sameStacks(before, inventory());
        if (step == Step.DONE) {
            settled = 0;
        }
    }

    private static List<ItemStack> inventory() {
        List<ItemStack> stacks = new ArrayList<>(InventoryUtil.WHOLE_INVENTORY);
        for (int slot = 0; slot < InventoryUtil.WHOLE_INVENTORY; slot++) {
            stacks.add(MC.player.getInventory().getItem(slot).copy());
        }
        return stacks;
    }

    private void fail(String reason) {
        stop();
        failure = reason;
        state = State.FAILED;
    }

    // A menu still on its way is closed when it comes.
    public void stop() {
        trip.stop();
        close();
        if (state == State.OPENING && clicked) {
            lateTicks = OPEN_TICKS + ServerInfo.pingTicks();
        }
        if (busy()) {
            state = State.DONE;
        }
    }

    // Once a tick. Closes a menu that opened after its visit was over. A screen another of our
    // helpers works is left to it.
    public void closeLate() {
        if (!waitsLate()) {
            lateTicks = 0;
        } else if (lateMenu() && InventoryUtil.containerOpen() && !WorkedScreens.showingBesides(this)) {
            MC.player.closeContainer();
            lateTicks = 0;
        } else {
            lateTicks--;
        }
    }

    public boolean waitsLate() {
        ClientLevel here = MC.level;
        return lateTicks > 0 && !busy() && here != null && here == clickedIn.get();
    }

    @Override
    public boolean ownsScreen() {
        return MC.player != null && InventoryUtil.containerOpen() && (menuOurs() || lateMenu());
    }

    private void close() {
        if (MC.player != null && menuOurs()) {
            MC.player.closeContainer();
        }
    }

    private boolean menuOurs() {
        int live = MC.player.containerMenu.containerId;
        return state == State.OPEN ? live == openedMenu
            : state == State.OPENING && clicked && live != clickedMenu && InventoryUtil.containerOpen();
    }

    private boolean lateMenu() {
        return waitsLate() && MC.player.containerMenu.containerId != clickedMenu;
    }
}
