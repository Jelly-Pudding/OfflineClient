package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.phys.Vec3;

public final class Spider extends Module {

    private final NumberSetting speed = new NumberSetting("Speed",
        "Climb speed in blocks per tick. Small numbers are already fast.",
        0.2, 0.1, 0.5, 0.05).max(1);

    public Spider() {
        super("Spider", "Climb up any wall like a spider.", Category.MOVEMENT);
        addSettings(speed);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !mc.player.horizontalCollision) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        // Faster upward motion from jumps and boosts is left alone.
        if (velocity.y >= 0.2) {
            return;
        }
        mc.player.setDeltaMovement(velocity.x, speed.getValue(), velocity.z);
    }
}
