package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.AreaPlacer;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.SpawnUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

public final class SpawnProofer extends AreaPlacer {

    private static final int TARGET_COLOR = 0xFFFFD040;

    private final NumberSetting light = new NumberSetting("Light",
        "Highest block light a spot may have.", 0, 0, 15, 1).min(0).max(15);

    public SpawnProofer() {
        super("SpawnProofer", "Lights up or fills the spots mobs would spawn in.",
            "What to place on a dark spot. Click to pick.",
            List.of(Blocks.TORCH, Blocks.SOUL_TORCH),
            "Outline the spots waiting to be blocked off.", 2, TARGET_COLOR);
        addSettings(range, light, blocks, perTick, delay, rotate, render);
        searchTags("torch", "spawn proof", "light");
    }

    // Dark spots in reach. Nearest first.
    @Override
    protected void collect() {
        int maxLight = light.getInt();
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (BlockUtil.intersectsPlayer(pos) || !BlockUtil.isReplaceable(pos)) {
                continue;
            }
            if (SpawnUtil.spawnable(pos, maxLight)) {
                targets.add(pos.immutable());
            }
        }
    }

    // The floor under the spot is always a sturdy face to click.
    @Override
    protected boolean placeOn(BlockPos pos) {
        return BlockUtil.place(pos, Direction.DOWN, rotate.isOn(), true);
    }
}
