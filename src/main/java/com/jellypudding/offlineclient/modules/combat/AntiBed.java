package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.module.RespawnBlockBreaker;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.BedBlock;

public final class AntiBed extends RespawnBlockBreaker {

    public AntiBed() {
        super("AntiBed", "Breaks an enemy bed placed next to you.", "beds");
        searchTags("bed bomb", "bed aura", "defence");
    }

    @Override
    protected boolean explodesHere() {
        BedRule rule = mc.level.environmentAttributes()
            .getValue(EnvironmentAttributes.BED_RULE, mc.player.blockPosition());
        return rule.explodes();
    }

    @Override
    protected boolean isThreat(BlockPos pos) {
        return BlockUtil.state(pos).getBlock() instanceof BedBlock && dangerous(pos);
    }
}
