package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TakeFrom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.Set;

// The server alone decides whether a sapling may grow. The client always answers a
// meal on one with a pass whilst the packet still goes out. A meal grows it only about
// half the time. Dark oak and pale oak grow only from a square of four and go in that way.
public final class TreeAura extends Module {

    // Open space a sapling needs over it. The common trees stand up to seven blocks tall.
    private static final int ROOM = 7;

    // Blocks an acacia trunk or its fork leans sideways at most. It leans one for each block it rises.
    private static final int LEAN = 3;

    // A sapling still standing after this many meals has no room the client can see.
    private static final int MAX_MEALS = 16;

    private static final int MAX_TRACKED = 256;

    private static final int SPOT_COLOR = 0xFF60E060;

    private static final Set<Block> SQUARE_ONLY = Set.of(Blocks.DARK_OAK_SAPLING, Blocks.PALE_OAK_SAPLING);

    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes to reach.", 4.5, 1, 6, 0.1).min(1);
    private final NumberSetting spacing = new NumberSetting("Spacing",
        "Blocks kept clear of saplings and logs around each new sapling.", 2, 0, 6, 1, " blocks").min(0);
    private final BoolSetting plant = new BoolSetting("Plant",
        "Plants saplings from your hotbar on dirt and grass with room for a tree.", true);
    private final BoolSetting feed = new BoolSetting("Bone meal",
        "Feeds bone meal to the saplings in reach until they grow.", true);
    private final EnumSetting<TakeFrom> takeFrom = TakeFrom.setting("bone meal", TakeFrom.HOTBAR).under(feed);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between one sapling or meal and the next.", 2, 0, 20, 1, " ticks");
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards each block on the server side.", true);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting render = new BoolSetting("Show spot",
        "Outlines where the next sapling goes.", true);

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();
    // Meals given to each sapling. One that never grows is left alone.
    private final Map<BlockPos, Integer> meals = new BoundedMap<>(MAX_TRACKED);
    private BlockPos nextSpot;
    private int timer;
    private int planted;

    public TreeAura() {
        super("TreeAura", "Plants saplings around you and grows them with bone meal.", Category.WORLD);
        addSettings(range, spacing, plant, feed, takeFrom, delay, rotate, swing, render);
        searchTags("sapling", "tree farm", "forest", "bone meal", "plant trees");
    }

    @Override
    public String getSuffix() {
        return count(planted, "planted");
    }

    @Override
    protected void onEnable() {
        meals.clear();
        nextSpot = null;
        timer = 0;
        planted = 0;
    }

    @Override
    protected void onDisable() {
        loan.giveBack();
        slots.restoreIfMine();
        nextSpot = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        nextSpot = null;
        if (!inGame() || mc.gameMode == null || mc.player.isSpectator() || mc.gui.screen() != null) {
            return;
        }
        if (mc.player.isDeadOrDying()) {
            // Respawn gives a fresh inventory. A borrowed slot could hold anything.
            loan.forget();
            slots.forget();
            return;
        }
        int saplingSlot = InventoryUtil.hotbarSlot(TreeAura::isSapling);
        Block sapling = saplingSlot == -1 ? null : blockIn(mc.player.getInventory().getItem(saplingSlot));
        if (plant.isOn() && sapling != null) {
            nextSpot = findSpot(sapling);
        }
        if (timer > 0) {
            timer--;
            return;
        }
        if (feed.isOn() && feedOne()) {
            timer = delay.getInt();
            return;
        }
        loan.giveBack();
        if (nextSpot != null && plantAt(saplingSlot, nextSpot)) {
            timer = delay.getInt();
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (render.isOn() && nextSpot != null) {
            event.getBatch().outlineBlock(nextSpot, SPOT_COLOR, false);
        }
    }

    private static boolean isSapling(ItemStack stack) {
        return blockIn(stack) instanceof SaplingBlock;
    }

    private static Block blockIn(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item ? item.getBlock() : null;
    }

    private boolean plantAt(int slot, BlockPos spot) {
        slots.select(slot);
        boolean placed = BlockUtil.place(spot, Direction.DOWN, rotate.isOn(), false);
        slots.restore();
        if (placed) {
            swing.getValue().swing(InteractionHand.MAIN_HAND);
            planted++;
        }
        return placed;
    }

    // True once a meal went out. The nearest sapling that can grow is fed first.
    private boolean feedOne() {
        BlockPos target = null;
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (growable(pos)) {
                target = pos;
                break;
            }
        }
        if (target == null || !loan.hold(Items.BONE_MEAL, takeFrom.getValue().limit())) {
            return false;
        }
        BlockUtil.useOn(target, rotate.isOn(), false);
        swing.getValue().swing(InteractionHand.MAIN_HAND);
        meals.merge(target, 1, Integer::sum);
        return true;
    }

    private boolean growable(BlockPos pos) {
        Block block = BlockUtil.state(pos).getBlock();
        if (!(block instanceof SaplingBlock) || meals.getOrDefault(pos, 0) >= MAX_MEALS || !hasRoom(pos)
            || trunkMeetsPlayer(pos, block)) {
            return false;
        }
        return !SQUARE_ONLY.contains(block) || inFullSquare(pos, block);
    }

    // A trunk grown through the player would bury them in logs. A square trunk reaches one
    // block past the sapling on every side and a leaning one a block further for each block up.
    private static boolean trunkMeetsPlayer(BlockPos pos, Block sapling) {
        AABB body = mc.player.getBoundingBox();
        int square = SQUARE_ONLY.contains(sapling) ? 1 : 0;
        for (int rise = 0; rise <= ROOM; rise++) {
            int side = Math.max(square, Math.min(rise, LEAN));
            if (new AABB(pos.above(rise)).inflate(side, 0, side).intersects(body)) {
                return true;
            }
        }
        return false;
    }

    // The sapling is one corner of a square of four of its kind.
    private static boolean inFullSquare(BlockPos pos, Block sapling) {
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                if (squareFilled(pos.offset(dx, 0, dz), sapling)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean squareFilled(BlockPos corner, Block sapling) {
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                if (!BlockUtil.state(corner.offset(dx, 0, dz)).is(sapling)) {
                    return false;
                }
            }
        }
        return true;
    }

    // The empty cell of the nearest footprint the sapling fits. A square already
    // started is finished before a new one begins.
    private BlockPos findSpot(Block sapling) {
        int size = SQUARE_ONLY.contains(sapling) ? 2 : 1;
        BlockPos best = null;
        int bestFilled = -1;
        for (BlockPos corner : BlockUtil.positionsWithin(range.getValue())) {
            int filled = 0;
            BlockPos empty = null;
            boolean fits = true;
            for (int dx = 0; dx < size && fits; dx++) {
                for (int dz = 0; dz < size && fits; dz++) {
                    BlockPos cell = corner.offset(dx, 0, dz);
                    if (BlockUtil.state(cell).is(sapling)) {
                        filled++;
                    } else if (!goodGround(cell, sapling)) {
                        fits = false;
                    } else if (empty == null) {
                        empty = cell;
                    }
                }
            }
            if (fits && empty != null && filled > bestFilled && clearAround(corner, size)) {
                best = empty;
                bestFilled = filled;
            }
        }
        return best;
    }

    private boolean goodGround(BlockPos cell, Block sapling) {
        BlockState state = BlockUtil.state(cell);
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty()
            || BlockUtil.distanceTo(cell) > range.getValue()) {
            return false;
        }
        return sapling.defaultBlockState().canSurvive(mc.level, cell) && hasRoom(cell)
            && !trunkMeetsPlayer(cell, sapling);
    }

    // The same test a growing tree makes of the space its trunk rises through.
    private static boolean hasRoom(BlockPos pos) {
        if (!mc.level.isInsideBuildHeight(pos.above(ROOM))) {
            return false;
        }
        for (int i = 1; i <= ROOM; i++) {
            if (!TreeFeature.validTreePos(mc.level, pos.above(i))) {
                return false;
            }
        }
        return true;
    }

    // No sapling and no log within the spacing of the footprint. Its own cells may hold saplings.
    private boolean clearAround(BlockPos corner, int size) {
        int gap = spacing.getInt();
        for (int dx = -gap; dx < size + gap; dx++) {
            for (int dz = -gap; dz < size + gap; dz++) {
                if (dx >= 0 && dx < size && dz >= 0 && dz < size) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    BlockState state = BlockUtil.state(corner.offset(dx, dy, dz));
                    if (state.getBlock() instanceof SaplingBlock || state.is(BlockTags.LOGS)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
