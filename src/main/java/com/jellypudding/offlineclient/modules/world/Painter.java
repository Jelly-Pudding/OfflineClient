package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.AreaPlacer;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

// A face is open when the spot in front of it is air or liquid or a plant. That spot
// is filled leaning on the covered block itself.
public final class Painter extends AreaPlacer {

    private static final int TARGET_COLOR = 0xFFE070FF;

    private final RegistryListSetting<Block> cover = new RegistryListSetting<>("Cover",
        "The blocks whose open faces get covered. Click to pick them.", BuiltInRegistries.BLOCK,
        List.of(Blocks.NETHERRACK));

    public Painter() {
        super("Painter", "Covers every open face of the chosen blocks in reach with a block from your hotbar.",
            "The blocks to cover them with. A block on both lists is never used. Click to pick them.",
            List.of(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.STONE, Blocks.DEEPSLATE),
            "Outline the spots waiting to be filled.", 1, TARGET_COLOR);
        addSettings(cover, blocks, range, wallsRange, perRound, delay, rotate, render);
        searchTags("cover", "coat", "skin", "replace faces");
    }

    // A paint block that is also covered would spread with every block it lays.
    @Override
    protected boolean allowed(Block block) {
        return super.allowed(block) && !cover.contains(block);
    }

    @Override
    protected void collect() {
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (!BlockUtil.blockFits(pos) || coveredSide(pos) == null) {
                continue;
            }
            if (inReach(pos)) {
                targets.add(pos);
            }
        }
    }

    @Override
    protected boolean placeOn(BlockPos pos) {
        Direction side = coveredSide(pos);
        if (side == null) {
            return false;
        }
        if (BlockUtil.opensOnClick(BlockUtil.state(pos.relative(side)))) {
            return BlockUtil.placeAny(pos, rotate.isOn(), true);
        }
        return BlockUtil.place(pos, side, rotate.isOn(), true);
    }

    // The side of the spot a covered block sits on. Null when none does.
    private Direction coveredSide(BlockPos pos) {
        for (Direction side : Direction.values()) {
            if (cover.contains(BlockUtil.state(pos.relative(side)).getBlock())) {
                return side;
            }
        }
        return null;
    }
}
