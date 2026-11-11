package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.JumpCarry;
import com.jellypudding.offlineclient.util.Lagback;
import com.jellypudding.offlineclient.util.MovementUtil;

public final class LongJump extends Module {

    // Average horizontal speed per tick of a normal sprint jump.
    private static final double BASE_SPEED = 0.35;

    private static final String TIMER_KEY = "longjump";

    private final NumberSetting multiplier = new NumberSetting("Multiplier",
        "How much further a jump carries you.",
        3, 1, 10, 0.5, "x").min(0.1);
    private final NumberSetting timer = new NumberSetting("Timer",
        "Speeds up the game whilst you move. 1 is normal.", 1, 1, 3, 0.1, "x");
    private final BoolSetting stopOnLagback = new BoolSetting("Stop on lagback",
        "Ends the boost when the server teleports you back.", true);

    private final JumpCarry carry = new JumpCarry();
    private final Lagback.Watcher lagback = new Lagback.Watcher();

    public LongJump() {
        super("LongJump", "Lets you jump much further than normal.", Category.MOVEMENT);
        addSettings(multiplier, timer, stopOnLagback);
    }

    @Override
    public String getSuffix() {
        return multiplier.getValueString();
    }

    @Override
    protected void onEnable() {
        lagback.sync();
        carry.stop();
    }

    @Override
    protected void onDisable() {
        Timer.override(TIMER_KEY, 1f);
    }

    // Called from LocalPlayerMixin once the game has set the jump velocity.
    public void onJump() {
        if (!isEnabled() || !inGame() || !MovementUtil.inPlainAir(mc.player)) {
            return;
        }
        carry.start(BASE_SPEED * multiplier.getValue());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (lagback.happened() && stopOnLagback.isOn()) {
            carry.stop();
        }
        Timer.override(TIMER_KEY, MovementUtil.hasInput() ? timer.getFloat() : 1f);
        carry.tick();
    }
}
