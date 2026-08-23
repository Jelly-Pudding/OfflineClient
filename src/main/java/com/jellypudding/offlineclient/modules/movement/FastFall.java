package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.phys.Vec3;

public final class FastFall extends Module {

    private final NumberSetting force = new NumberSetting("Force",
        "Extra downward pull whilst falling.", 0.5, 0.1, 3, 0.1);

    public FastFall() {
        super("FastFall", "Pulls you to the ground faster when you are falling.", Category.MOVEMENT);
        addSettings(force);
        searchTags("heavy boots", "gravity", "fall faster");
    }

    @Override
    public String getSuffix() {
        return force.getValueString();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.onGround()) {
            return;
        }
        if (mc.player.isFallFlying() || mc.player.getAbilities().flying
            || mc.player.isInWater() || mc.player.isInLava() || mc.player.onClimbable()) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        if (velocity.y >= 0) {
            return;
        }
        mc.player.addDeltaMovement(new Vec3(0, -force.getValue() * 0.08, 0));
    }
}
