package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.BlockUseAura;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BlockTurns;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

public final class TillAura extends BlockUseAura {

    private final BoolSetting nearWater = new BoolSetting("Only near water",
        "Only tills soil with water within four blocks of it. Farmland further out dries up again.", false);

    private int tilled;

    public TillAura() {
        super("TillAura", "Tills the dirt around you with the hoe in your hand.", "Multi till");
        addSettings(range, multi, lineOfSight, nearWater, face, swing);
        searchTags("hoe aura", "farmland", "auto till");
    }

    @Override
    public String getSuffix() {
        return count(tilled, "tilled");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        tilled = 0;
    }

    @Override
    protected void work() {
        ItemStack hoe = mc.player.getMainHandItem();
        if (!hoe.is(ItemTags.HOES)) {
            return;
        }
        tilled += click(inReach(pos -> tillable(hoe, pos)), BlockTurns::use);
    }

    // Farmland only forms under open air. Rooted dirt under a block would lose its roots for nothing.
    private boolean tillable(ItemStack hoe, BlockPos pos) {
        return BlockUtil.state(pos.above()).isAir() && BlockTurns.result(hoe, pos) != null
            && (!nearWater.isOn() || BlockTurns.staysWet(mc.level, pos));
    }
}
