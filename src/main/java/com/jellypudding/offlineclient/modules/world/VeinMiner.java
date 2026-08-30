package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mining an ore mines every connected ore of the same kind too.
 * Deepslate and stone variants count as one vein.
 */
public final class VeinMiner extends Module {

    public enum Targets { ORES, ORES_AND_LOGS, ANY_BLOCK }

    private static final int OUTLINE_COLOR = 0xFFFFA020;

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
        .describe(Targets.ANY_BLOCK, "Any run of matching blocks.");
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn toward each block on the server side.", true);

    private final Set<BlockPos> vein = new LinkedHashSet<>();
    private String veinFamily;
    private BlockPos current;
    private BlockPos minedByHand;
    private int waitTicks;

    public VeinMiner() {
        super("VeinMiner", "Mines a whole vein of ore when you break one block of it.", Category.WORLD);
        addSettings(range, delay, maxBlocks, targets, rotate);
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
        if (!BlockMiner.mine(current, rotate.isOn())) {
            vein.remove(current);
            current = null;
        }
    }

    private void prune() {
        vein.removeIf(pos -> {
            BlockState state = BlockUtil.state(pos);
            return state.isAir()
                || !BlockUtil.family(state.getBlock()).equals(veinFamily)
                || BlockUtil.distanceTo(pos) > range.getValue();
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
                        if (vein.contains(next) || BlockUtil.distanceTo(next) > range.getValue()) {
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

    private boolean isTarget(BlockPos pos, BlockState state) {
        if (state.isAir() || !BlockUtil.isBreakable(pos)) {
            return false;
        }
        return switch (targets.getValue()) {
            case ORES -> BlockUtil.isOre(state);
            case ORES_AND_LOGS -> BlockUtil.isOre(state) || state.is(BlockTags.LOGS);
            case ANY_BLOCK -> !state.getShape(mc.level, pos).isEmpty();
        };
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        for (BlockPos pos : vein) {
            AABB box = new AABB(pos).deflate(pos.equals(current) ? DrawBatch.BLOCK_INSET : 0.06);
            event.getBatch().outlineBox(box, OUTLINE_COLOR, false);
        }
    }
}
