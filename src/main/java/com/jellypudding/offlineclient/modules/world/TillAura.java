package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.BlockUseAura;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BlockTurns;
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
        tilled += click(inReach(pos -> BlockTurns.tills(hoe, pos, nearWater.isOn())), BlockTurns::use);
    }
}
