package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

// The client is not told how long the server has counted and the ticks are counted here.
final class PortalStay extends Condition {

    private final NumberSetting stay;
    private int ticks;

    PortalStay(Frame frame) {
        super(frame, "In a portal");
        stay = add(new NumberSetting(frame.name("In a portal"),
            "You have stood in a nether portal for this many seconds. Unless the server changed it a portal"
                + " takes a survival player away after four.", 2, 0.5, 4, 0.5, " seconds").min(0));
    }

    @Override
    public void start() {
        ticks = 0;
    }

    @Override
    public boolean holds() {
        BlockPos feet = mc.player.blockPosition();
        boolean inside = BlockUtil.state(feet).is(Blocks.NETHER_PORTAL)
            || BlockUtil.state(feet.above()).is(Blocks.NETHER_PORTAL);
        ticks = inside ? ticks + 1 : 0;
        return ticks >= stay.getValue() * SharedConstants.TICKS_PER_SECOND;
    }

    @Override
    public String reason() {
        return "You had stood in a portal for " + stay.getValueString() + ".";
    }
}
