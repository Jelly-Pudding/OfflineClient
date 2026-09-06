package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.RespawnBlockBreaker;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.phys.Vec3;

public final class AntiAnchor extends RespawnBlockBreaker {

    // A hit this far up the head block lands a slab in its upper half.
    private static final double UPPER_HALF = 0.75;

    private final BoolSetting headSlab = new BoolSetting("Head slab",
        "Puts a slab over your head when an anchor sits above you. It only fits whilst you crouch.", true);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH).under(headSlab);

    private final SlotSwap slabSlots = new SlotSwap();

    public AntiAnchor() {
        super("AntiAnchor", "Breaks an enemy respawn anchor placed next to you.", "anchors");
        addSettings(headSlab, swing);
        searchTags("respawn anchor", "anchor aura", "defence");
    }

    // An anchor that sets a spawn point never goes off.
    @Override
    protected boolean explodesHere() {
        return ExplosionUtil.anchorsExplodeHere();
    }

    @Override
    protected boolean isThreat(BlockPos pos) {
        return BlockUtil.state(pos).getBlock() == Blocks.RESPAWN_ANCHOR && dangerous(pos);
    }

    @Override
    protected void onDisable() {
        super.onDisable();
        slabSlots.restore();
    }

    @Subscribe
    private void onSlabTick(TickEvent event) {
        if (!headSlab.isOn() || !inGame() || mc.player.isSpectator() || !explodesHere()) {
            return;
        }
        BlockPos head = mc.player.blockPosition().above();
        if (BlockUtil.state(head.above()).getBlock() != Blocks.RESPAWN_ANCHOR
            || !BlockUtil.state(head).isAir()) {
            return;
        }
        int slot = BlockUtil.findBlockSlot(block -> block instanceof SlabBlock);
        if (slot == -1) {
            return;
        }
        slabSlots.select(slot);
        Vec3 hit = Vec3.atLowerCornerOf(head).add(0.5, UPPER_HALF, 0.5);
        if (BlockUtil.placeDirect(head, hit, rotate.isOn(), false)) {
            swing.getValue().swing();
        }
        slabSlots.restore();
    }
}
