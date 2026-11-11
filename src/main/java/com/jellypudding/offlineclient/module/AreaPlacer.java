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

// Shared by modules that fill nearby positions from a chosen block list.
// A subclass picks the positions and how each is clicked. The rest is one round a tick.
// A subclass that first has to clear a block out of the way digs it through here as well.
public abstract class AreaPlacer extends Module {

    // Alpha the targets beyond this round are drawn at.
    private static final int QUEUED_ALPHA = 0x80;

    protected final NumberSetting range;
    protected final NumberSetting wallsRange;
    protected final RegistryListSetting<Block> blocks;
    protected final NumberSetting perRound;
    protected final NumberSetting delay;
    protected final BoolSetting rotate;
    protected final BoolSetting render;

    // Refilled by collect every tick. The first few are placed and all are drawn.
    protected final List<BlockPos> targets = new ArrayList<>();

    private final SlotSwap slots = new SlotSwap();
    private final int color;
    private int timer;
    // The block being dug out of the way. Null whilst none is.
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

    // Refills targets with the positions worth placing on.
    protected abstract void collect();

    // Clicks one position. True once a placement has gone out.
    protected abstract boolean placeOn(BlockPos pos);

    // True when the spot is in reach and either in plain sight or inside the walls range.
    protected boolean inReach(BlockPos pos) {
        if (!withinRange(pos, range.getValue())) {
            return false;
        }
        return withinRange(pos, wallsRange.getValue()) || BlockUtil.canSee(pos);
    }

    // Straight line distance from the eyes. A subclass may measure another way.
    protected boolean withinRange(BlockPos pos, double reach) {
        return BlockUtil.distanceTo(pos) <= reach;
    }

    // Which blocks may be taken from the hotbar. A subclass may invert the list.
    protected boolean allowed(Block block) {
        return blocks.contains(block);
    }

    // How many blocks go down this round. A subclass may cap it lower.
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

    // Digs one block in reach that passes the test for as long as this is called every tick. The
    // order says which goes first. Letting go of a block that stopped passing at once keeps it
    // from passing for the block of another module.
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

    // The block dig is working on. Null whilst it has none.
    protected final BlockPos digging() {
        return digging;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        targets.clear();
        if (mc.player.isSpectator()) {
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
