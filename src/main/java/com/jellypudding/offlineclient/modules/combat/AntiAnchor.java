package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.module.RespawnBlockBreaker;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.Blocks;

public final class AntiAnchor extends RespawnBlockBreaker {

    public AntiAnchor() {
        super("AntiAnchor", "Breaks an enemy respawn anchor placed next to you.", "anchors");
        searchTags("respawn anchor", "anchor aura", "defence");
    }

    // An anchor that sets a spawn point never goes off.
    @Override
    protected boolean explodesHere() {
        return !mc.level.environmentAttributes()
            .getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, mc.player.blockPosition());
    }

    @Override
    protected boolean isThreat(BlockPos pos) {
        return BlockUtil.state(pos).getBlock() == Blocks.RESPAWN_ANCHOR && dangerous(pos);
    }
}
