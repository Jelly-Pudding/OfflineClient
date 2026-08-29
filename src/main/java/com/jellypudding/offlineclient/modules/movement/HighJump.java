package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.JumpCarry;
import com.jellypudding.offlineclient.util.Modules;

public final class HighJump extends Module {

    private final NumberSetting boost = new NumberSetting("Boost",
        "Extra upward motion added to every jump.", 0.5, 0.1, 2, 0.1);
    private final BoolSetting keepSpeed = new BoolSetting("Keep speed",
        "Holds your take off speed for the whole jump instead of slowing in the air.", false);

    private final JumpCarry carry = new JumpCarry();

    public HighJump() {
        super("HighJump", "Jump higher than normal.", Category.MOVEMENT);
        addSettings(boost, keepSpeed);
    }

    @Override
    protected void onDisable() {
        carry.stop();
    }

    // Called from LocalPlayerMixin.getJumpPower().
    public float getAdditionalJumpMotion() {
        return isEnabled() ? boost.getFloat() : 0f;
    }

    // Called from LocalPlayerMixin once the game has set the jump velocity.
    public void onJump() {
        // LongJump owns the horizontal speed whilst it is on.
        if (!isEnabled() || !keepSpeed.isOn() || !inGame() || Modules.enabled(LongJump.class)) {
            return;
        }
        carry.start(mc.player.getDeltaMovement().horizontalDistance());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        carry.tick();
    }
}
