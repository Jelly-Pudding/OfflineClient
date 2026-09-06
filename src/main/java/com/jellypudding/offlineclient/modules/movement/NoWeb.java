package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.level.block.Blocks;

// The full speed mode lives in WebBlockMixin.
public final class NoWeb extends Module {

    public enum Mode { FULL_SPEED, TIMER }

    private static final String TIMER_KEY = "noweb";

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How a cobweb is beaten.", Mode.FULL_SPEED)
        .describe(Mode.FULL_SPEED, "The cobweb never slows you at all.")
        .describe(Mode.TIMER, "Speeds the game up whilst you hang in a cobweb off the ground.");
    private final NumberSetting timerSpeed = new NumberSetting("Timer speed",
        "Game speed whilst in a cobweb.", 10, 1, 20, 0.5, "x")
        .min(1).under(mode, Mode.TIMER);

    public NoWeb() {
        super("NoWeb", "Move through cobwebs at full speed.", Category.MOVEMENT);
        addSettings(mode, timerSpeed);
        searchTags("cobweb", "web");
    }

    @Override
    protected void onDisable() {
        Timer.override(TIMER_KEY, 1f);
    }

    public boolean skipsWebs() {
        return isEnabled() && mode.is(Mode.FULL_SPEED);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        boolean hanging = mode.is(Mode.TIMER) && !mc.player.onGround()
            && mc.player.getInBlockState().is(Blocks.COBWEB);
        Timer.override(TIMER_KEY, hanging ? timerSpeed.getFloat() : 1f);
    }
}
