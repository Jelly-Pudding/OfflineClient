package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class Parkour extends Module {

    public Parkour() {
        super("Parkour", "Jumps for you at the edge of blocks.", Category.MOVEMENT);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !mc.player.onGround() || mc.player.isShiftKeyDown()
            || mc.options.keyJump.isDown()) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        if (velocity.horizontalDistanceSqr() < 1e-6) {
            return;
        }

        // The shrunk box still counts the very lip of a block as ground.
        AABB under = mc.player.getBoundingBox()
            .move(0, -0.5, 0)
            .inflate(-0.001, 0, -0.001);
        if (mc.level.noCollision(mc.player, under)) {
            mc.player.jumpFromGround();
        }
    }
}
