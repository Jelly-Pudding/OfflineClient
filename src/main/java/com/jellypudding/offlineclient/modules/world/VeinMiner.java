package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Mining an ore mines every connected ore of the same kind too.
// Deepslate and stone variants count as one vein.
public final class VeinMiner extends Module {

    public enum Targets { ORES, ORES_AND_LOGS, ANY_BLOCK, LIST }

    public enum ListMode { WHITELIST, BLACKLIST }

    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes a vein block may be.", 4.5, 1, 6, 0.1).min(1).max(6);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between one block and the next.", 0, 0, 20, 1, " ticks").min(0);
    private final NumberSetting maxBlocks = new NumberSetting("Max blocks",
        "The most blocks one vein may contain.", 32, 1, 128, 1).min(1);
    private final EnumSetting<Targets> targets = new EnumSetting<>("Targets",
        "Which block types count as a vein.", Targets.ORES)
        .describe(Targets.ORES, "Only ore veins.")
        .describe(Targets.ORES_AND_LOGS, "Ore veins and tree trunks.")
        .describe(Targets.ANY_BLOCK, "Any run of matching blocks.")
        .describe(Targets.LIST, "Whatever the list below allows.");
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks the list applies to. Click to pick them.", BuiltInRegistries.BLOCK,
        List.of(Blocks.STONE, Blocks.DIRT, Blocks.GRASS_BLOCK))
        .under(targets, Targets.LIST);
    private final EnumSetting<ListMode> listMode = new EnumSetting<>("List mode",
        "What the list means.", ListMode.BLACKLIST)
        .describe(ListMode.WHITELIST, "Only the listed blocks start a vein.")
        .describe(ListMode.BLACKLIST, "Everything but the listed blocks starts a vein.")
        .under(targets, Targets.LIST);
    private final BoolSetting flat = new BoolSetting("Flat",
        "Never mines a vein block below your feet so the floor stays under you.", false);
    private final BoolSetting lineOfSight = new BoolSetting("Line of sight",
        "Skips vein blocks your eyes cannot see.", false);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards each block on the server side.", true);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting render = new BoolSetting("Render",
        "Draws a box on every block in the vein.", true);
    private final BoxStyle veinBox = new BoxStyle(BoxStyle.Shape.BOTH, 30).under(render);

    private final Set<BlockPos> vein = new LinkedHashSet<>();
    private String veinFamily;
    private BlockPos current;
    private BlockPos minedByHand;
    private int waitTicks;

    public VeinMiner() {
        super("VeinMiner", "Mines a whole vein of ore when you break one block of it.", Category.WORLD);
        addSettings(range, delay, maxBlocks, targets, blocks, listMode, flat, lineOfSight,
            rotate, swing, render);
        addSettings(veinBox.settings());
        searchTags("vein", "ore", "mining");
    }

    @Override
    public String getSuffix() {
        return count(vein.size());
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        clear();
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        clear();
    }

    private void clear() {
        vein.clear();
        veinFamily = null;
        current = null;
        minedByHand = null;
        waitTicks = 0;
    }

    // Fires for the block mined by hand.
    @Subscribe
    private void onBlockBreak(BlockBreakEvent event) {
        if (BlockMiner.isSelfCall() || !inGame()) {
            return;
        }
        BlockPos pos = event.getPos();
        minedByHand = pos;
        if (vein.contains(pos)) {
            return;
        }
        BlockState state = BlockUtil.state(pos);
        if (isTarget(pos, state)) {
            build(pos, state);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        BlockPos byHand = minedByHand;
        minedByHand = null;
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        prune();
        if (vein.isEmpty()) {
            current = null;
            return;
        }
        // The block under the crosshair keeps its vanilla mining.
        if (byHand != null && vein.contains(byHand)) {
            current = null;
            return;
        }
        if (current != null && BlockUtil.state(current).isAir()) {
            current = null;
            waitTicks = delay.getInt();
        }
        if (waitTicks > 0) {
            waitTicks--;
            BlockMiner.keepControl();
            return;
        }
        if (current == null || !vein.contains(current)) {
            current = vein.stream()
                .min(Comparator.comparingDouble(BlockUtil::distanceTo))
                .orElse(null);
        }
        if (current == null) {
            return;
        }
        if (!BlockMiner.mine(current, rotate.isOn(), swing.getValue())) {
            vein.remove(current);
            current = null;
        }
    }

    private void prune() {
        vein.removeIf(pos -> {
            BlockState state = BlockUtil.state(pos);
            return state.isAir()
                || !BlockUtil.family(state.getBlock()).equals(veinFamily)
                || !reachable(pos);
        });
        if (vein.isEmpty()) {
            veinFamily = null;
        }
    }

    // Flood fills from the origin. Corners count as touching.
    private void build(BlockPos origin, BlockState state) {
        vein.clear();
        veinFamily = BlockUtil.family(state.getBlock());
        current = null;
        waitTicks = 0;

        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        vein.add(origin);
        int limit = maxBlocks.getInt();
        while (!queue.isEmpty() && vein.size() < limit) {
            BlockPos here = queue.poll();
            for (int dx = -1; dx <= 1 && vein.size() < limit; dx++) {
                for (int dy = -1; dy <= 1 && vein.size() < limit; dy++) {
                    for (int dz = -1; dz <= 1 && vein.size() < limit; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = here.offset(dx, dy, dz);
                        if (vein.contains(next) || !reachable(next)) {
                            continue;
                        }
                        BlockState nextState = BlockUtil.state(next);
                        if (nextState.isAir() || !BlockUtil.family(nextState.getBlock()).equals(veinFamily)) {
                            continue;
                        }
                        vein.add(next);
                        queue.add(next);
                    }
                }
            }
        }
    }

    // Range plus the optional floor guard and line of sight test.
    private boolean reachable(BlockPos pos) {
        if (BlockUtil.distanceTo(pos) > range.getValue()) {
            return false;
        }
        if (flat.isOn() && pos.getY() < mc.player.getBlockY()) {
            return false;
        }
        return !lineOfSight.isOn() || BlockUtil.canSee(pos);
    }

    private boolean isTarget(BlockPos pos, BlockState state) {
        if (state.isAir() || !BlockUtil.isBreakable(pos)) {
            return false;
        }
        if (state.getShape(mc.level, pos).isEmpty()) {
            return false;
        }
        return switch (targets.getValue()) {
            case ORES -> BlockUtil.isOre(state);
            case ORES_AND_LOGS -> BlockUtil.isOre(state) || state.is(BlockTags.LOGS);
            case ANY_BLOCK -> true;
            case LIST -> blocks.contains(state.getBlock()) == listMode.is(ListMode.WHITELIST);
        };
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        veinBox.drawAll(event.getBatch(), vein, false);
    }
}
