package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.AreaPlacer;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.SpawnUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;

import java.util.Comparator;
import java.util.List;

public final class SpawnProofer extends AreaPlacer {

    private static final int TARGET_COLOR = 0xFFFFD040;

    public enum Mode { ALWAYS, POTENTIAL, BOTH }

    private final NumberSetting light = new NumberSetting("Light",
        "Highest block light a spot may have.", 0, 0, 15, 1).min(0).max(15);
    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Which dark spots are worth blocking off.", Mode.BOTH)
        .describe(Mode.ALWAYS, "Only spots that stay dark in daylight.")
        .describe(Mode.POTENTIAL, "Only spots the sky lights up by day.")
        .describe(Mode.BOTH, "Every spot dark enough to spawn a mob.");

    public SpawnProofer() {
        super("SpawnProofer", "Lights up or fills the spots mobs would spawn in.",
            "What to place on a dark spot. Click to pick.",
            List.of(Blocks.TORCH, Blocks.SOUL_TORCH),
            "Outline the spots waiting to be blocked off.", 2, TARGET_COLOR);
        addSettings(range, wallsRange, light, mode, blocks, perTick, delay, rotate, render);
        searchTags("torch", "spawn proof", "light");
    }

    // Dark spots in reach. Darkest first when a torch is going down.
    @Override
    protected void collect() {
        int maxLight = light.getInt();
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (BlockUtil.intersectsPlayer(pos) || !BlockUtil.isReplaceable(pos)) {
                continue;
            }
            if (!SpawnUtil.spawnable(pos, maxLight, false) || !wantedMode(pos, maxLight)) {
                continue;
            }
            if (!inReach(pos)) {
                continue;
            }
            targets.add(pos.immutable());
        }
        if (holdingLight()) {
            targets.sort(Comparator.comparingInt(pos -> mc.level.getMaxLocalRawBrightness(pos)));
        }
    }

    // A spot the sky reaches only spawns mobs at night.
    private boolean wantedMode(BlockPos pos, int maxLight) {
        if (mode.is(Mode.BOTH)) {
            return true;
        }
        boolean daylit = mc.level.getBrightness(LightLayer.SKY, pos) > maxLight;
        return daylit == mode.is(Mode.POTENTIAL);
    }

    // One torch at a time so the next spot is judged with its light already in.
    @Override
    protected int roundSize() {
        return holdingLight() ? 1 : super.roundSize();
    }

    private boolean holdingLight() {
        if (mc.player == null) {
            return false;
        }
        int slot = BlockUtil.findBlockSlot(this::allowed);
        if (slot == -1) {
            return false;
        }
        return mc.player.getInventory().getItem(slot).getItem() instanceof BlockItem item
            && item.getBlock().defaultBlockState().getLightEmission() > 0;
    }

    // The floor under the spot is always a sturdy face to click.
    @Override
    protected boolean placeOn(BlockPos pos) {
        return BlockUtil.place(pos, Direction.DOWN, rotate.isOn(), true);
    }
}
