package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.AnvilBlock;

// Catches an anvil dropped on you with a block placed under it.
public final class AntiAnvil extends Module {

    // Anvils are placed with this much air under them at least.
    private static final int LOWEST_ANVIL = 3;

    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward the block.", true);

    private final SlotSwap slots = new SlotSwap();

    public AntiAnvil() {
        super("AntiAnvil", "Blocks anvils dropped on your head.", Category.COMBAT);
        addSettings(rotate);
        searchTags("anvil");
    }

    @Override
    protected void onEnable() {
        slots.forget();
    }

    @Override
    protected void onDisable() {
        slots.restore();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        BlockPos feet = mc.player.blockPosition();
        int reach = (int) mc.player.blockInteractionRange();
        for (int i = 0; i <= reach; i++) {
            BlockPos anvil = feet.above(LOWEST_ANVIL + i);
            if (!(BlockUtil.state(anvil).getBlock() instanceof AnvilBlock)
                || !BlockUtil.state(anvil.below()).isAir()) {
                continue;
            }
            if (cover(anvil.below())) {
                return;
            }
        }
        slots.restore();
    }

    private boolean cover(BlockPos target) {
        int slot = BlockUtil.findBlastProofSlot();
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        Direction support = BlockUtil.findPlaceSupport(target);
        boolean placed = support != null
            ? BlockUtil.place(target, support, rotate.isOn(), true)
            : BlockUtil.placeDirect(target, rotate.isOn(), true);
        slots.restore();
        return placed;
    }
}
