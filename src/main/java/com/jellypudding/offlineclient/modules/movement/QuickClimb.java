package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.phys.Vec3;

public final class QuickClimb extends Module {

    private final NumberSetting speed = new NumberSetting("Speed",
        "Climbing speed where vanilla is 0.15.", 0.3, 0.15, 2, 0.05);

    public QuickClimb() {
        super("QuickClimb", "Climb ladders and vines much faster.", Category.MOVEMENT);
        addSettings(speed);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !mc.player.onClimbable()) {
            return;
        }
        // Vanilla climbs whilst pushing into the ladder or holding jump.
        if (!mc.player.horizontalCollision && !mc.options.keyJump.isDown()) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(velocity.x, speed.getValue(), velocity.z);
    }
}
