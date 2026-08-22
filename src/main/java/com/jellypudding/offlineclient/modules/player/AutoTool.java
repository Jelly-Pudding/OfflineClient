package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.world.level.block.state.BlockState;

public final class AutoTool extends Module {

    public AutoTool() {
        super("AutoTool", "Switches to your best tool when you mine something.", Category.PLAYER);
    }

    @Subscribe
    private void onBlockBreak(BlockBreakEvent event) {
        if (!inGame()) {
            return;
        }
        BlockState state = mc.level.getBlockState(event.getPos());
        int selected = mc.player.getInventory().getSelectedSlot();
        int best = selected;
        // Efficiency counts. A fast enchanted tool beats a plain better one.
        float bestSpeed = ItemUtil.miningSpeed(mc.player.getInventory().getItem(selected), state);

        for (int i = 0; i < 9; i++) {
            float speed = ItemUtil.miningSpeed(mc.player.getInventory().getItem(i), state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }

        if (best != selected) {
            mc.player.getInventory().setSelectedSlot(best);
        }
    }
}
