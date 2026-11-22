package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.UseBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public abstract class AreaPlacer extends Module {

    private static final int QUEUED_ALPHA = 0x80;

    protected final NumberSetting range;
    protected final NumberSetting wallsRange;
    protected final RegistryListSetting<Block> blocks;
    protected final NumberSetting perRound;
    protected final NumberSetting delay;
    protected final BoolSetting rotate;
    protected final BoolSetting render;

    // Refilled every tick. The first few are placed and all are drawn.
    protected final List<BlockPos> targets = new ArrayList<>();

    private final SlotSwap slots = new SlotSwap();
    private final int color;
    private int timer;
    private BlockPos digging;

    protected AreaPlacer(String name, String description, String blocksDescription,
                         List<Block> defaultBlocks, String renderDescription,
                         int defaultDelay, int color) {
        super(name, description, Category.WORLD);
        this.color = color;
        range = new NumberSetting("Range",
            "How far you can reach to place.", 4.5, 1, 6, 0.1, " blocks");
        wallsRange = new NumberSetting("Walls range",
            "How far to place with no clear view from your eyes.", 4.5, 0, 6, 0.1, " blocks");
        blocks = new RegistryListSetting<>("Blocks", blocksDescription,
            BuiltInRegistries.BLOCK, defaultBlocks);
        perRound = new NumberSetting("Blocks per round",
            "How many blocks to place in one round.", 1, 1, 8, 1);
        delay = new NumberSetting("Delay",
            "Ticks to wait between placing rounds.", defaultDelay, 0, 20, 1, " ticks");
        rotate = new BoolSetting("Rotate",
            "Sends a look packet towards each block.", true);
        render = new BoolSetting("Show targets", renderDescription, true);
    }

    protected abstract void collect();

    // True once a placement has gone out.
    protected abstract boolean placeOn(BlockPos pos);

    protected boolean inReach(BlockPos pos) {
        if (!withinRange(pos, range.getValue())) {
            return false;
        }
        return withinRange(pos, wallsRange.getValue()) || BlockUtil.canSee(pos);
    }

    protected boolean withinRange(BlockPos pos, double reach) {
        return BlockUtil.distanceTo(pos) <= reach;
    }

    protected boolean allowed(Block block) {
        return blocks.contains(block);
    }

    protected int roundSize() {
        return perRound.getInt();
    }

    @Override
    public String getSuffix() {
        return count(targets.size());
    }

    @Override
    protected void onEnable() {
        timer = 0;
        slots.forget();
        targets.clear();
    }

    @Override
    protected void onDisable() {
        stopDigging();
        slots.restoreIfMine();
        targets.clear();
    }

    // Letting go of a block that stopped passing at once keeps it from passing for the block of
    // another module.
    protected final void dig(Predicate<BlockPos> wanted, Comparator<BlockPos> order) {
        if (Hands.taken()) {
            stopDigging();
            return;
        }
        if (digging != null && !wanted.test(digging)) {
            stopDigging();
        }
        if (digging == null) {
            digging = BlockUtil.positionsWithin(range.getValue()).stream().filter(wanted).min(order).orElse(null);
        }
        if (digging != null && !BlockMiner.minesOther(digging) && !BlockMiner.mine(digging, rotate.isOn())) {
            digging = null;
        }
    }

    protected final void stopDigging() {
        if (digging != null) {
            BlockMiner.release(digging);
            digging = null;
        }
    }

    protected final BlockPos digging() {
        return digging;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        targets.clear();
        if (Hands.tied()) {
            return;
        }
        collect();
        if (targets.isEmpty() || Hands.taken()) {
            slots.restore();
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        // Paper drops use packets past its limit. The rest of the round waits for a later tick.
        int round = Math.min(roundSize(), UseBudget.remaining());
        if (round == 0) {
            return;
        }

        int slot = BlockUtil.findBlockSlot(this::allowed);
        if (slot == -1) {
            slots.restore();
            return;
        }
        slots.select(slot);

        int placed = 0;
        for (BlockPos pos : targets) {
            if (placed >= round) {
                break;
            }
            if (placeOn(pos)) {
                placed++;
            }
        }
        if (placed > 0) {
            timer = delay.getInt();
        }
        slots.restore();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        int next = roundSize();
        for (int i = 0; i < targets.size(); i++) {
            int argb = i < next ? color : ColorUtil.withAlpha(color, QUEUED_ALPHA);
            event.getBatch().outlineBlock(targets.get(i), argb, false);
        }
    }
}
