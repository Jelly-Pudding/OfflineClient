package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape shared by the modules that fill nearby positions from a chosen
 * list of blocks. A subclass says which positions it wants and how each one
 * is clicked. Everything else is the same round of placements a tick.
 */
public abstract class AreaPlacer extends Module {

    // Alpha the targets beyond this round are drawn at.
    private static final int QUEUED_ALPHA = 0x80;

    protected final NumberSetting range;
    protected final RegistryListSetting<Block> blocks;
    protected final NumberSetting perTick;
    protected final NumberSetting delay;
    protected final BoolSetting rotate;
    protected final BoolSetting render;

    // Refilled by collect every tick. The first few are placed and all are drawn.
    protected final List<BlockPos> targets = new ArrayList<>();

    private final SlotSwap slots = new SlotSwap();
    private final int color;
    private int timer;

    protected AreaPlacer(String name, String description, String blocksDescription,
                         List<Block> defaultBlocks, String renderDescription,
                         int defaultDelay, int color) {
        super(name, description, Category.WORLD);
        this.color = color;
        range = new NumberSetting("Range",
            "How far you can reach to place.", 4.5, 1, 6, 0.1).min(1);
        blocks = new RegistryListSetting<>("Blocks", blocksDescription,
            BuiltInRegistries.BLOCK, defaultBlocks);
        perTick = new NumberSetting("Blocks per tick",
            "How many blocks to place in one round.", 1, 1, 8, 1).min(1);
        delay = new NumberSetting("Delay",
            "Ticks to wait between placing rounds.", defaultDelay, 0, 20, 1, " ticks");
        rotate = new BoolSetting("Rotate",
            "Send a look packet towards each block.", true);
        render = new BoolSetting("Show targets", renderDescription, true);
    }

    // Refills targets with the positions worth placing on.
    protected abstract void collect();

    // Clicks one position. True once a placement has gone out.
    protected abstract boolean placeOn(BlockPos pos);

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
        slots.restoreIfMine();
        targets.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        targets.clear();
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        collect();
        if (targets.isEmpty()) {
            slots.restore();
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        int slot = BlockUtil.findBlockSlot(blocks::contains);
        if (slot == -1) {
            slots.restore();
            return;
        }
        slots.select(slot);

        int placed = 0;
        for (BlockPos pos : targets) {
            if (placed >= perTick.getInt()) {
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
        int next = perTick.getInt();
        for (int i = 0; i < targets.size(); i++) {
            int argb = i < next ? color : ColorUtil.withAlpha(color, QUEUED_ALPHA);
            event.getBatch().outlineBlock(targets.get(i), argb, false);
        }
    }
}
