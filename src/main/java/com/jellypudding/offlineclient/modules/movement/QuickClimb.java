package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.phys.Vec3;

public final class QuickClimb extends Module {

    public enum Mode { VELOCITY, TIMER }

    private static final String TIMER_KEY = "quickclimb";

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the climb is sped up.", Mode.VELOCITY)
        .describe(Mode.VELOCITY, "Sets your climbing speed straight to the value below.")
        .describe(Mode.TIMER, "Speeds the whole game up whilst you climb.");
    private final NumberSetting speed = new NumberSetting("Speed",
        "How fast you climb in blocks a tick. Vanilla is 0.2.", 0.3, 0.15, 2, 0.05, " blocks")
        .under(mode, Mode.VELOCITY);
    private final NumberSetting timer = new NumberSetting("Timer",
        "Game speed whilst climbing. 1 does nothing.", 1.44, 1, 3, 0.01, "x")
        .min(1).under(mode, Mode.TIMER);

    public QuickClimb() {
        super("QuickClimb", "Climb ladders and vines and powder snow much faster.", Category.MOVEMENT);
        addSettings(mode, speed, timer);
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onDisable() {
        Timer.override(TIMER_KEY, 1f);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        boolean climbing = inGame() && climbing();
        if (mode.is(Mode.TIMER)) {
            Timer.override(TIMER_KEY, climbing ? timer.getFloat() : 1f);
            return;
        }
        Timer.override(TIMER_KEY, 1f);
        if (!climbing) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(velocity.x, speed.getValue(), velocity.z);
    }

    // Vanilla climbs whilst pushing into the ladder or holding jump.
    // Leather boots make powder snow climbable the same way.
    private boolean climbing() {
        if (!mc.player.horizontalCollision && !mc.options.keyJump.isDown()) {
            return false;
        }
        if (mc.player.onClimbable()) {
            return true;
        }
        return mc.player.getInBlockState().is(Blocks.POWDER_SNOW)
            && PowderSnowBlock.canEntityWalkOnPowderSnow(mc.player);
    }
}
