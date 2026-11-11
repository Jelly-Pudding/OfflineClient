package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.AreaPlacer;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// One block comes out at a time and its hole is filled before the next is dug. Nothing is dug
// whilst the hotbar has no block to put back.
public final class BlockSwap extends AreaPlacer {

    private static final int TARGET_COLOR = 0xFF70D050;
    // Alpha of the outline round the block being dug out.
    private static final int DIG_ALPHA = 0x80;
    // A hole this far past the range is given up. You walked away from it.
    private static final double HOLE_SLACK = 2;

    private final RegistryListSetting<Block> takeOut = new RegistryListSetting<>("Take out",
        "The blocks that get dug out. They start as the ones that spoil a lawn. Click to pick them.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.DIRT_PATH, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT));

    private final WorldWatch world = new WorldWatch();
    private final ChatWarning noBlock = new ChatWarning();
    // Spots this module dug or is digging and the tick each was last seen without its block. A
    // spot is kept a moment after it is filled. The server may still turn the block down.
    private final Map<BlockPos, Integer> holes = new HashMap<>();

    public BlockSwap() {
        super("BlockSwap", "Digs out the listed blocks in reach and puts a block from your hotbar in each hole.",
            "The blocks that go into the holes. A block on both lists is never dug out. Click to pick them.",
            List.of(Blocks.GRASS_BLOCK), "Outline the holes waiting for their block.", 0, TARGET_COLOR);
        addSettings(takeOut, blocks, range, wallsRange, perRound, delay, rotate, render);
        searchTags("lawn", "replace", "swap", "grass", "returf");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        world.accept();
        holes.clear();
        noBlock.clear();
    }

    @Override
    protected void onDisable() {
        super.onDisable();
        holes.clear();
    }

    // Runs before the placing round.
    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.player.isSpectator()) {
            return;
        }
        if (world.changed()) {
            stopDigging();
            holes.clear();
        }
        forgetHoles();
        if (BlockUtil.findBlockSlot(this::allowed) == -1) {
            stopDigging();
            if (BlockUtil.nearestWithin(range.getValue(), this::comesOut) != null) {
                noBlock.say("BlockSwap has no block in the hotbar to put in the holes.");
            }
            return;
        }
        noBlock.clear();
        // A hole is filled before the next block comes out.
        if (holes.keySet().stream().anyMatch(BlockUtil::isReplaceable)) {
            stopDigging();
            return;
        }
        dig(this::comesOut, Comparator.comparingDouble(BlockUtil::distanceTo));
        if (digging() != null) {
            holes.put(digging(), mc.player.tickCount);
        }
    }

    // A hole you walked away from is given up. One that has held a block for as long as the
    // server takes to answer is done.
    private void forgetHoles() {
        int now = mc.player.tickCount;
        double far = range.getValue() + HOLE_SLACK;
        BlockPos dug = digging();
        holes.entrySet().removeIf(hole -> {
            BlockPos pos = hole.getKey();
            if (BlockUtil.distanceTo(pos) > far) {
                return true;
            }
            if (pos.equals(dug) || BlockUtil.isReplaceable(pos)) {
                hole.setValue(now);
                return false;
            }
            // The player clock starts again on a respawn.
            int filled = now - hole.getValue();
            return filled < 0 || filled > ServerInfo.answerTicks();
        });
    }

    // The block underfoot waits until you move off it.
    private boolean comesOut(BlockPos pos) {
        Block block = BlockUtil.state(pos).getBlock();
        return takeOut.contains(block) && !allowed(block) && inReach(pos) && BlockUtil.diggable(pos)
            && !BlockUtil.isStandingOn(pos);
    }

    @Override
    protected void collect() {
        for (BlockPos pos : holes.keySet()) {
            if (BlockUtil.blockFits(pos) && inReach(pos)) {
                targets.add(pos);
            }
        }
    }

    @Override
    protected boolean placeOn(BlockPos pos) {
        return BlockUtil.placeAny(pos, rotate.isOn(), true);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (render.isOn() && digging() != null) {
            event.getBatch().outlineBlock(digging(), ColorUtil.withAlpha(TARGET_COLOR, DIG_ALPHA), false);
        }
    }
}
