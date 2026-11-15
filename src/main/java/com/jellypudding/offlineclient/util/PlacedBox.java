package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.path.Trip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

// A shulker box or an ender chest set down beside you then opened for the owner's clicks and
// mined back and picked up. A shulker box drops as an item with what it holds. An ender chest
// only comes back to a Silk Touch pickaxe and is left standing without one. The item often
// lands out of reach and is then walked onto.
public final class PlacedBox implements WorkedScreens.Worker {

    // What the owner does whilst the container is open. True whilst there is more to do.
    @FunctionalInterface
    public interface Clicks {
        boolean step(AbstractContainerMenu menu);
    }

    private enum Stage { NONE, OPEN, BREAK, FETCH }

    private static final Minecraft MC = OfflineClient.MC;

    // Ticks between two clicks that ask the container to open.
    private static final int REOPEN_TICKS = 10;
    // Ticks the container gets to open before it is mined back unopened and to break before it is left standing.
    private static final int OPEN_LIMIT = 60;
    private static final int BREAK_LIMIT = 200;
    // Ticks an item lies before the game hands it to anyone with two more for the server's answer.
    private static final int PICKUP_DELAY = 12;
    // Ticks the walk onto a container that fell out of reach gets.
    private static final int FETCH_LIMIT = 100;
    // A container that breaks lands within this many blocks of where it stood.
    private static final double DROP_RADIUS = 3;

    // Where each container set down stands until it is mined back or given up. Read from the
    // network thread.
    private static final Set<BlockPos> WORKING = ConcurrentHashMap.newKeySet();

    private final InventoryUtil.SlotSwap slots;
    private final Trip walk = new Trip();
    private Stage stage = Stage.NONE;
    private BlockPos pos;
    // The level the container went down in. Held weakly to keep no level left behind alive.
    private WeakReference<ClientLevel> level = new WeakReference<>(null);
    private int ticks;
    private int sinceClick;
    // The menu open as the container went down. Only one opened after it is the container's.
    private int menuBefore;
    private boolean opened;
    private boolean wouldNotOpen;

    // The owner's slot swap picks the container and the tool that mines it back.
    public PlacedBox(InventoryUtil.SlotSwap slots) {
        this.slots = slots;
        WorkedScreens.add(this);
    }

    // The block behind you at floor level or one beside it. The floor under it has to take the
    // click and the block over it has to be free for a shulker lid to lift into. Null when no
    // block will do.
    public static BlockPos spot() {
        return spot(pos -> true);
    }

    // The same with a test of the owner's own that the spot has to pass as well.
    public static BlockPos spot(Predicate<BlockPos> also) {
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
                && BlockUtil.state(above).getCollisionShape(MC.level, above).isEmpty() && also.test(spot)) {
                return spot;
            }
        }
        return null;
    }

    // True whilst a container set down here is worked. Its clicks open no container of yours.
    public static boolean working(BlockPos pos) {
        return WORKING.contains(pos);
    }

    // True whilst a container another owner set down is worked. Its screen would take this
    // owner's clicks.
    public boolean othersAtWork() {
        return WORKING.size() > (pos == null ? 0 : 1);
    }

    // True whilst any owner has a container down. Another screen that opens meanwhile and the
    // container's own could be taken for each other.
    public static boolean anyAtWork() {
        return !WORKING.isEmpty();
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
        level = new WeakReference<>(MC.level);
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
        if (stage == Stage.FETCH) {
            return fetch();
        }
        BlockState state = BlockUtil.state(pos);
        if (!standing(state)) {
            // The container is an item on the ground from here on.
            if (stage == Stage.BREAK) {
                BlockMiner.release();
            }
            WORKING.remove(pos);
            stage = Stage.FETCH;
            ticks = 0;
            return true;
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
        AbstractContainerMenu menu = openMenu(state);
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
        walk.stop();
        stage = Stage.NONE;
        pos = null;
    }

    // The game hands an item over once it has lain a moment with the player beside it. A
    // container that still lies there after that fell out of reach.
    private boolean fetch() {
        ItemEntity lying = dropped();
        if (++ticks > FETCH_LIMIT || lying == null && ticks > PICKUP_DELAY) {
            end();
            return false;
        }
        if (lying != null && ticks > PICKUP_DELAY) {
            if (!walk.active()) {
                walk.start(lying.blockPosition(), 0);
            }
            walk.tick();
        }
        return true;
    }

    // The container lying near where it broke. Null once it is picked up.
    private ItemEntity dropped() {
        List<ItemEntity> lying = MC.level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(DROP_RADIUS),
            item -> item.isAlive() && container(item.getItem()));
        return lying.isEmpty() ? null : lying.getFirst();
    }

    private static boolean container(ItemStack stack) {
        return stack.is(Items.ENDER_CHEST) || Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock;
    }

    // A container left in another level owns nothing here. Menu ids start again on a new connection.
    @Override
    public boolean ownsScreen() {
        ClientLevel here = MC.level;
        if (stage != Stage.OPEN || here == null || here != level.get() || !InventoryUtil.containerOpen()) {
            return false;
        }
        AbstractContainerMenu live = MC.player.containerMenu;
        return live.containerId != menuBefore && ownKind(BlockUtil.state(pos), live);
    }

    // The container's own screen once its items have come in.
    private AbstractContainerMenu openMenu(BlockState state) {
        AbstractContainerMenu menu = InventoryUtil.openedSince(menuBefore);
        return menu != null && ownKind(state, menu) ? menu : null;
    }

    // The server names no block as it opens a screen. A shulker box opens a screen of its own kind
    // and an ender chest one with its own title.
    private static boolean ownKind(BlockState state, AbstractContainerMenu menu) {
        if (state.is(Blocks.ENDER_CHEST)) {
            return InventoryUtil.isEnderChest(MC.gui.screen());
        }
        return state.getBlock() instanceof ShulkerBoxBlock && menu.menuType == MenuType.SHULKER_BOX;
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

    // True whilst a shulker box or an ender chest stands at the position.
    static boolean standing(BlockPos pos) {
        return standing(BlockUtil.state(pos));
    }

    private static boolean standing(BlockState state) {
        return state.getBlock() instanceof ShulkerBoxBlock || state.is(Blocks.ENDER_CHEST);
    }
}
