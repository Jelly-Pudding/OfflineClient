package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// A shulker box or an ender chest set down beside you then opened for the owner's clicks and
// mined back. A shulker box drops as an item with what it holds. An ender chest only comes
// back to a Silk Touch pickaxe and is left standing without one.
public final class PlacedBox {

    // What the owner does whilst the container is open. True whilst there is more to do.
    @FunctionalInterface
    public interface Clicks {
        boolean step(AbstractContainerMenu menu);
    }

    private enum Stage { NONE, OPEN, BREAK }

    private static final Minecraft MC = OfflineClient.MC;

    // Ticks between two clicks that ask the container to open.
    private static final int REOPEN_TICKS = 10;
    // Ticks the container gets to open before it is mined back unopened and to break before it is left standing.
    private static final int OPEN_LIMIT = 60;
    private static final int BREAK_LIMIT = 200;

    // Where each container set down stands until it is mined back or given up. Read from the
    // network thread.
    private static final Set<BlockPos> WORKING = ConcurrentHashMap.newKeySet();

    private final InventoryUtil.SlotSwap slots;
    private Stage stage = Stage.NONE;
    private BlockPos pos;
    private int ticks;
    private int sinceClick;
    // The menu open as the container went down. Only one opened after it is the container's.
    private int menuBefore;
    private boolean opened;
    private boolean wouldNotOpen;

    // The owner's slot swap picks the container and the tool that mines it back.
    public PlacedBox(InventoryUtil.SlotSwap slots) {
        this.slots = slots;
    }

    // The block behind you at floor level or one beside it. The floor under it has to take the
    // click and the block over it has to be free for a shulker lid to lift into. Null when no
    // block will do.
    public static BlockPos spot() {
        BlockPos feet = MC.player.blockPosition();
        List<BlockPos> tries = new ArrayList<>();
        tries.add(feet.relative(MC.player.getDirection().getOpposite()));
        for (Direction side : Direction.Plane.HORIZONTAL) {
            tries.add(feet.relative(side));
        }
        for (BlockPos spot : tries) {
            BlockPos floor = spot.below();
            BlockPos above = spot.above();
            if (BlockUtil.isReplaceable(spot) && !BlockUtil.intersectsPlayer(spot)
                && BlockUtil.isSolid(floor) && !BlockUtil.opensOnClick(BlockUtil.state(floor))
                && BlockUtil.state(above).getCollisionShape(MC.level, above).isEmpty()) {
                return spot;
            }
        }
        return null;
    }

    // True whilst a container set down here is worked. Its clicks open no container of yours.
    public static boolean working(BlockPos pos) {
        return WORKING.contains(pos);
    }

    // Puts the container held in the hotbar slot down against the floor of the spot. A shulker
    // box then faces up and lifts its lid into the free block above. False when it did not go
    // down.
    public boolean setDown(int hotbarSlot, BlockPos spot, boolean rotate) {
        slots.select(hotbarSlot);
        if (!BlockUtil.place(spot, Direction.DOWN, rotate, true)) {
            return false;
        }
        pos = spot.immutable();
        WORKING.add(pos);
        stage = Stage.OPEN;
        ticks = 0;
        sinceClick = REOPEN_TICKS;
        menuBefore = MC.player.containerMenu.containerId;
        opened = false;
        wouldNotOpen = false;
        return true;
    }

    public boolean active() {
        return stage != Stage.NONE;
    }

    // True once the container's own screen opened after it went down.
    public boolean opened() {
        return opened;
    }

    // True when the container stood the whole wait without opening. It is mined back unopened.
    public boolean wouldNotOpen() {
        return wouldNotOpen;
    }

    // One tick of the cycle. False once it is over. The clicks run every tick the container is
    // open and the container is closed and mined back once they are done.
    public boolean tick(Clicks clicks, boolean rotatePlace, boolean rotateMine) {
        if (stage == Stage.NONE) {
            return false;
        }
        BlockState state = BlockUtil.state(pos);
        if (!standing(state)) {
            end();
            return false;
        }
        if (++ticks > (stage == Stage.OPEN ? OPEN_LIMIT : BREAK_LIMIT)) {
            if (stage == Stage.BREAK) {
                end();
                return false;
            }
            // One that will not open is mined back all the same.
            wouldNotOpen = true;
            stage = Stage.BREAK;
            ticks = 0;
        }
        if (stage == Stage.BREAK) {
            return mineBack(state, rotateMine);
        }
        AbstractContainerMenu menu = openMenu();
        if (menu == null) {
            if (++sinceClick > REOPEN_TICKS) {
                BlockUtil.useOn(pos, rotatePlace, true);
                sinceClick = 0;
            }
            return true;
        }
        opened = true;
        // The wait only counts whilst the container is shut.
        ticks = 0;
        if (clicks.step(menu)) {
            return true;
        }
        MC.player.closeContainer();
        stage = Stage.BREAK;
        return true;
    }

    public void end() {
        if (stage == Stage.BREAK) {
            BlockMiner.release();
        }
        if (pos != null) {
            WORKING.remove(pos);
        }
        stage = Stage.NONE;
        pos = null;
    }

    // The container's own screen once its items have come in.
    private AbstractContainerMenu openMenu() {
        if (!(MC.gui.screen() instanceof AbstractContainerScreen<?> screen) || screen instanceof InventoryScreen) {
            return null;
        }
        AbstractContainerMenu menu = screen.getMenu();
        return menu.containerId != menuBefore && menu.getStateId() != 0 ? menu : null;
    }

    private boolean mineBack(BlockState state, boolean rotate) {
        boolean ender = state.is(Blocks.ENDER_CHEST);
        int tool = ItemUtil.bestToolSlot(state, 1,
            stack -> !ender || ItemUtil.enchantLevel(Enchantments.SILK_TOUCH, stack) > 0, InventoryUtil.HOTBAR_SIZE);
        if (ender && tool == -1) {
            end();
            return false;
        }
        if (tool != -1) {
            slots.select(tool);
        }
        BlockMiner.mineWithHeld(pos, rotate);
        return true;
    }

    private static boolean standing(BlockState state) {
        return state.getBlock() instanceof ShulkerBoxBlock || state.is(Blocks.ENDER_CHEST);
    }
}
