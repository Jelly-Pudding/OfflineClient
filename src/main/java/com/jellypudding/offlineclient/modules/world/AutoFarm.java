package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Cuts the ripe crops around you and puts the seeds back in the ground. It
 * never walks anywhere and only farms what is already in reach.
 */
public final class AutoFarm extends Module {

    private record Seed(Item item, Block support) {
    }

    // The crop that was cut here and the tick the spot is given up on.
    private record Planting(Block crop, int expiry) {
    }

    private static final Map<Block, Seed> SEEDS = Map.of(
        Blocks.WHEAT, new Seed(Items.WHEAT_SEEDS, Blocks.FARMLAND),
        Blocks.CARROTS, new Seed(Items.CARROT, Blocks.FARMLAND),
        Blocks.POTATOES, new Seed(Items.POTATO, Blocks.FARMLAND),
        Blocks.BEETROOTS, new Seed(Items.BEETROOT_SEEDS, Blocks.FARMLAND),
        Blocks.NETHER_WART, new Seed(Items.NETHER_WART, Blocks.SOUL_SAND));

    // Ticks before the same block is sent again.
    private static final int RETRY_TICKS = 10;

    // Ticks a slower block gets on top of its break time before it is given up on.
    private static final int SLOW_GRACE_TICKS = 20;

    // Ticks a cut spot waits for its seed.
    private static final int PLANT_TICKS = 60;

    // Vanilla repeats a held right click at this rate.
    private static final int BONEMEAL_INTERVAL = 4;

    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes to farm.", 4.5, 1, 6, 0.1).max(6);
    private final BoolSetting harvest = new BoolSetting("Harvest",
        "Breaks the crops that have finished growing.", true);
    private final BoolSetting replant = new BoolSetting("Replant",
        "Puts a seed back into every spot you just cleared.", true);
    private final BoolSetting bonemeal = new BoolSetting("Bonemeal",
        "Feeds bone meal to the crops that are still growing.", false);
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many crops to cut each tick.", 4, 1, 16, 1);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the spot on the server side before planting.", true);

    private final Map<BlockPos, Integer> attempted = new HashMap<>();
    private final Map<BlockPos, Planting> plantings = new HashMap<>();

    private BlockPos slowPending;
    private int slowDeadline;
    private int lastBonemeal;
    private int lastTick;

    private final SlotSwap slots = new SlotSwap();

    public AutoFarm() {
        super("AutoFarm", "Harvests the ripe crops in reach and replants them.", Category.WORLD);
        addSettings(range, harvest, replant, bonemeal, perTick, rotate);
        searchTags("farm", "crops", "harvest", "replant", "wheat");
    }

    @Override
    public String getSuffix() {
        return range.getValueString();
    }

    // Break packets for anything slower than one hit share the one server slot.
    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
        slots.restoreIfMine();
    }

    private void reset() {
        attempted.clear();
        plantings.clear();
        slowPending = null;
        lastBonemeal = 0;
        lastTick = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            slots.restoreIfMine();
            return;
        }
        // A held attack or an open container means the player is busy by hand.
        if (InputUtil.physicallyHeld(mc.options.keyAttack) || mc.player.isUsingItem() || mc.gui.screen() != null) {
            slots.restoreIfMine();
            return;
        }

        int now = mc.player.tickCount;
        // The tick count restarts on a respawn.
        if (now < lastTick) {
            reset();
        }
        lastTick = now;
        attempted.values().removeIf(expiry -> expiry <= now);
        plantings.values().removeIf(planting -> planting.expiry() <= now);
        if (slowPending != null && (now >= slowDeadline || !ripe(slowPending))) {
            slowPending = null;
        }

        List<BlockPos> scan = BlockUtil.positionsWithin(range.getValue());
        if (harvest.isOn()) {
            harvestTick(scan, now);
        }
        if (!plantTick(scan, now)) {
            slots.restoreIfMine();
        }
    }

    private void harvestTick(List<BlockPos> scan, int now) {
        int sent = 0;
        for (BlockPos pos : scan) {
            if (sent >= perTick.getInt()) {
                break;
            }
            if (attempted.containsKey(pos)) {
                continue;
            }
            BlockState state = BlockUtil.state(pos);
            if (!ripe(pos, state)) {
                continue;
            }
            boolean instant = BlockUtil.canInstantBreak(pos);
            if (!instant && slowPending != null) {
                continue;
            }
            BlockMiner.breakInstantly(pos);
            if (instant) {
                attempted.put(pos, now + RETRY_TICKS);
            } else {
                slowPending = pos;
                slowDeadline = now + BlockUtil.breakTicks(pos) + SLOW_GRACE_TICKS;
                attempted.put(pos, slowDeadline);
            }
            if (SEEDS.containsKey(state.getBlock())) {
                plantings.put(pos, new Planting(state.getBlock(), now + PLANT_TICKS));
            }
            sent++;
        }
        if (sent > 0) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    // One hand action a tick. The rotation manager only carries one angle anyway.
    private boolean plantTick(List<BlockPos> scan, int now) {
        if (replant.isOn() && plant()) {
            return true;
        }
        if (!bonemeal.isOn() || now - lastBonemeal < BONEMEAL_INTERVAL) {
            return false;
        }
        BlockPos target = findGrowing(scan);
        if (target == null) {
            return false;
        }
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.BONE_MEAL));
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        if (BlockUtil.useOn(target, rotate.isOn(), true)) {
            lastBonemeal = now;
        }
        return true;
    }

    private boolean plant() {
        Iterator<Map.Entry<BlockPos, Planting>> it = plantings.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Planting> entry = it.next();
            BlockPos pos = entry.getKey();
            Seed seed = SEEDS.get(entry.getValue().crop());
            if (!BlockUtil.state(pos).isAir() || !BlockUtil.state(pos.below()).is(seed.support())) {
                continue;
            }
            if (BlockUtil.distanceTo(pos) > range.getValue()) {
                it.remove();
                continue;
            }
            int slot = InventoryUtil.hotbarSlot(stack -> stack.is(seed.item()));
            if (slot == -1) {
                continue;
            }
            slots.select(slot);
            if (BlockUtil.place(pos, Direction.DOWN, rotate.isOn(), true)) {
                it.remove();
            }
            return true;
        }
        return false;
    }

    private BlockPos findGrowing(List<BlockPos> scan) {
        for (BlockPos pos : scan) {
            BlockState state = BlockUtil.state(pos);
            Block block = state.getBlock();
            if (block instanceof CropBlock crop && !crop.isMaxAge(state)) {
                return pos;
            }
            if (block instanceof CocoaBlock && state.getValue(CocoaBlock.AGE) < CocoaBlock.MAX_AGE) {
                return pos;
            }
        }
        return null;
    }

    private boolean ripe(BlockPos pos) {
        return ripe(pos, BlockUtil.state(pos));
    }

    private boolean ripe(BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        if (block instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
        }
        if (block instanceof CocoaBlock) {
            return state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
        }
        if (block == Blocks.MELON || block == Blocks.PUMPKIN) {
            return true;
        }
        // Stalks are cut above their bottom segment to keep the plant growing.
        if (block == Blocks.SUGAR_CANE || block == Blocks.CACTUS || block == Blocks.BAMBOO) {
            return BlockUtil.state(pos.below()).is(block);
        }
        if (block == Blocks.KELP || block == Blocks.KELP_PLANT) {
            return BlockUtil.state(pos.below()).is(Blocks.KELP_PLANT);
        }
        return false;
    }
}
