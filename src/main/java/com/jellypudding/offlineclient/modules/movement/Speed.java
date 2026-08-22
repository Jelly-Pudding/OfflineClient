package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public final class Speed extends Module {

    private static final double BASE_SPEED = 0.2806;

    private final NumberSetting multiplier = new NumberSetting("Multiplier",
        "Ground speed multiplier.", 1.6, 1, 10, 0.1, "x").min(0);
    private final BoolSetting keepInAir = new BoolSetting("Keep in air",
        "Keeps your speed whilst airborne.", true);
    private final NumberSetting timer = new NumberSetting("Timer",
        "Also speeds up the game whilst you move. 1 does nothing.", 1, 1, 3, 0.1, "x").min(1);

    public Speed() {
        super("Speed", "Move faster on the ground.", Category.MOVEMENT);
        addSettings(multiplier, keepInAir, timer);
    }

    @Override
    protected void onDisable() {
        OfflineClient.INSTANCE.getModuleManager()
            .get(com.jellypudding.offlineclient.modules.misc.Timer.class)
            .setOverride(com.jellypudding.offlineclient.modules.misc.Timer.OFF);
    }

    @Override
    public String getSuffix() {
        return multiplier.getValueString();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        // The timer boost applies whilst moving like the standalone module
        // but scoped to this one.
        boolean moving = mc.player.input.getMoveVector().length() > 1e-4f;
        OfflineClient.INSTANCE.getModuleManager()
            .get(com.jellypudding.offlineclient.modules.misc.Timer.class)
            .setOverride(timer.getValue() > 1 && moving
                ? timer.getFloat() : com.jellypudding.offlineclient.modules.misc.Timer.OFF);

        if (mc.player.isShiftKeyDown()) {
            return;
        }
        // Water and ladders and gliding have their own physics.
        if (mc.player.isInWater() || mc.player.isInLava() || mc.player.onClimbable()
            || mc.player.isFallFlying() || mc.player.getAbilities().flying) {
            return;
        }
        if (!mc.player.onGround() && !keepInAir.isOn()) {
            return;
        }
        Vec2 move = mc.player.input.getMoveVector();
        if (move.length() < 1e-4f) {
            return;
        }

        double speed = BASE_SPEED * multiplier.getValue();
        double angle = Math.toRadians(mc.player.getYRot()) + Math.atan2(-move.x, move.y);
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(-Math.sin(angle) * speed, velocity.y, Math.cos(angle) * speed);
    }
}
