package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
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
        0, 0, 1, 0.05);
    private final BoolSetting repeat = new BoolSetting("Repeat",
        "The bind switches a dash that repeats on its own on and off. Off dashes once a press.", false);
    private final NumberSetting interval = new NumberSetting("Interval",
        "Ticks from one dash to the next.", 20, 1, 100, 1, " ticks").under(repeat);

    private int wait;

    public Boost() {
        super("Boost", "Its bind dashes you the way you move or straight ahead.", Category.MOVEMENT);
        addSettings(strength, lift, repeat, interval);
        searchTags("dash", "blink dash", "burst");
    }

    // A single dash is the bind's own doing and leaves nothing to switch on.
    @Override
    public boolean isTogglable() {
        return repeat.isOn();
    }

    // A dash left repeating would start by itself in the next world joined.
    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public void onKeybind() {
        if (repeat.isOn()) {
            toggle();
        } else {
            dash();
        }
    }

    @Override
    protected void onEnable() {
        wait = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!repeat.isOn()) {
            setEnabled(false);
        } else if (--wait <= 0) {
            wait = interval.getInt();
            dash();
        }
    }

    private void dash() {
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
