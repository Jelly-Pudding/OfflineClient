package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.world.phys.Vec3;

public final class FastFall extends Module {

    private final NumberSetting force = new NumberSetting("Force",
        "Extra gravity whilst you fall. One doubles it.", 0.5, 0.1, 3, 0.1);

    public FastFall() {
        super("FastFall", "Pulls you to the ground faster when you are falling.", Category.MOVEMENT);
        addSettings(force);
        searchTags("heavy boots", "gravity", "fall faster");
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.FALL_CONTROL;
    }

    @Override
    public String getSuffix() {
        return force.getValueString();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.onGround() || !MovementUtil.inPlainAir(mc.player)) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        if (velocity.y >= 0) {
            return;
        }
        mc.player.addDeltaMovement(new Vec3(0, -force.getValue() * mc.player.getGravity(), 0));
    }
}
