package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;

public final class TillAura extends Module {

    private static final Set<Block> TILLABLE = Set.of(
        Blocks.GRASS_BLOCK, Blocks.DIRT_PATH, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT);

    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes to till.", 5, 1, 6, 0.1);
    private final BoolSetting multi = new BoolSetting("Multi till",
        "Tills every block in reach at once. Fast but obvious to an anti cheat.", false);
    private final BoolSetting lineOfSight = new BoolSetting("Line of sight",
        "Only tills blocks you can see from where you stand.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the block on the server side.", true);

    private int tilled;

    public TillAura() {
        super("TillAura", "Tills the dirt around you with the hoe in your hand.", Category.WORLD);
        addSettings(range, multi, lineOfSight, rotate);
        searchTags("hoe aura", "farmland", "auto till");
    }

    @Override
    public String getSuffix() {
        return count(tilled, "tilled");
    }

    @Override
    protected void onEnable() {
        tilled = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.gameMode == null || mc.player.isSpectator() || mc.gui.screen() != null) {
            return;
        }
        if (mc.gameMode.isDestroying() || mc.player.isHandsBusy()) {
            return;
        }
        if (!mc.player.getMainHandItem().is(ItemTags.HOES)) {
            return;
        }
        if (!multi.isOn() && mc.rightClickDelay > 0) {
            return;
        }
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (!tillable(pos)) {
                continue;
            }
            if (BlockUtil.useOn(pos, rotate.isOn(), !multi.isOn())) {
                tilled++;
                if (!multi.isOn()) {
                    mc.rightClickDelay = InputUtil.USE_DELAY;
                    return;
                }
            }
        }
    }

    // Farmland only forms under open air.
    private boolean tillable(BlockPos pos) {
        if (!TILLABLE.contains(BlockUtil.state(pos).getBlock()) || !BlockUtil.state(pos.above()).isAir()) {
            return false;
        }
        return !lineOfSight.isOn() || BlockUtil.canSee(pos);
    }
}
