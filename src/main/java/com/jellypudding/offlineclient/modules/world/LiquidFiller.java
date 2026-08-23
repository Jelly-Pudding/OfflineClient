package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class LiquidFiller extends Module {

    public enum Order { CLOSEST, FURTHEST, TOP_DOWN, BOTTOM_UP }

    private final NumberSetting range = new NumberSetting("Range",
        "How far you can reach to place.", 4.5, 1, 6, 0.1).min(1);
    private final BoolSetting water = new BoolSetting("Water",
        "Fill water.", true);
    private final BoolSetting lava = new BoolSetting("Lava",
        "Fill lava.", true);
    private final BoolSetting flowing = new BoolSetting("Flowing",
        "Also fill flowing liquid and not just the source blocks.", false);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "Blocks allowed to be used as filler. Click to pick them.", BuiltInRegistries.BLOCK,
        List.of(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.DIRT, Blocks.STONE,
            Blocks.DEEPSLATE, Blocks.NETHERRACK, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE));
    private final EnumSetting<Order> order = new EnumSetting<>("Order",
        "Which block gets filled first.", Order.CLOSEST);
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many blocks to place in one round.", 1, 1, 8, 1).min(1);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between placing rounds.", 1, 0, 20, 1, " ticks");
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward each block.", true);
    private final BoolSetting render = new BoolSetting("Show targets",
        "Outline the liquid waiting to be filled.", true);

    private final List<BlockPos> targets = new ArrayList<>();
    private final SlotSwap slots = new SlotSwap();
    private int timer;

    public LiquidFiller() {
        super("LiquidFiller", "Fills the water and lava around you with solid blocks.",
            Category.WORLD);
        addSettings(range, water, lava, flowing, blocks, order, perTick, delay, rotate, render);
        searchTags("lava", "water", "fill");
    }

    @Override
    public String getSuffix() {
        return targets.isEmpty() ? null : String.valueOf(targets.size());
    }

    @Override
    protected void onEnable() {
        timer = 0;
        slots.forget();
        targets.clear();
    }

    @Override
    protected void onDisable() {
        slots.restore();
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
            Direction support = BlockUtil.findPlaceSupport(pos);
            boolean ok = support != null
                ? BlockUtil.place(pos, support, rotate.isOn(), true)
                : BlockUtil.placeDirect(pos, rotate.isOn(), true);
            if (ok) {
                placed++;
            }
        }
        if (placed > 0) {
            timer = delay.getInt();
        }
        slots.restore();
    }

    private void collect() {
        // positionsWithin already hands them back nearest first.
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (!isWanted(pos) || BlockUtil.intersectsPlayer(pos)) {
                continue;
            }
            if (!mc.level.isUnobstructed(Blocks.STONE.defaultBlockState(), pos, CollisionContext.empty())) {
                continue;
            }
            targets.add(pos.immutable());
        }
        sort();
    }

    private boolean isWanted(BlockPos pos) {
        FluidState fluid = mc.level.getFluidState(pos);
        if (fluid.isEmpty() || (!flowing.isOn() && !fluid.isSource())) {
            return false;
        }
        if (!BlockUtil.isReplaceable(pos)) {
            return false;
        }
        if (fluid.is(FluidTags.WATER)) {
            return water.isOn();
        }
        return fluid.is(FluidTags.LAVA) && lava.isOn();
    }

    private void sort() {
        switch (order.getValue()) {
            case CLOSEST -> {
                // Already nearest first.
            }
            case FURTHEST -> targets.sort(
                Comparator.comparingDouble((BlockPos pos) -> BlockUtil.distanceTo(pos)).reversed());
            case TOP_DOWN -> targets.sort(
                Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed());
            case BOTTOM_UP -> targets.sort(
                Comparator.comparingInt((BlockPos pos) -> pos.getY()));
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        int next = perTick.getInt();
        for (int i = 0; i < targets.size(); i++) {
            int color = i < next ? 0xFF60D0FF : 0x8060D0FF;
            event.getBatch().outlineBlock(targets.get(i), color, false);
        }
    }
}
