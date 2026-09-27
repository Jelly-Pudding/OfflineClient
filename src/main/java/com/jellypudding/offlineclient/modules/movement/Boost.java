package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.world.phys.Vec3;

// The server pulls back any single move of ten blocks or more. The dash sets the
// speed of the first tick under that and friction eats it from there.
public final class Boost extends Module {

    // Ten blocks less the most a sprint with Speed II adds to the first tick.
    private static final double MAX_STRENGTH = 9.5;

    private final NumberSetting strength = new NumberSetting("Strength",
        "Blocks the dash covers in its first tick. The server pulls back a move of ten blocks.",
        3, 0.5, MAX_STRENGTH, 0.5, " blocks").min(0.1).max(MAX_STRENGTH);
    private final NumberSetting lift = new NumberSetting("Lift",
        "Upward speed the dash starts with. A real jump is 0.42 and nought keeps you on the ground.",
        0, 0, 1, 0.05).min(0);

    public Boost() {
        super("Boost", "Press the bind to dash the way you move or straight ahead.", Category.MOVEMENT);
        addSettings(strength, lift);
        searchTags("dash", "blink dash", "burst");
    }

    // The bind dashes. There is nothing to switch on.
    @Override
    public boolean isTogglable() {
        return false;
    }

    @Override
    public void onKeybind() {
        if (!inGame() || mc.player.isPassenger()) {
            return;
        }
        Vec3 heading = MovementUtil.inputDirection();
        if (heading == Vec3.ZERO) {
            heading = Vec3.directionFromRotation(0, mc.player.getYRot());
        }
        Vec3 push = heading.scale(strength.getValue());
        double rise = Math.max(mc.player.getDeltaMovement().y, lift.getValue());
        mc.player.setDeltaMovement(push.x, rise, push.z);
    }
}
