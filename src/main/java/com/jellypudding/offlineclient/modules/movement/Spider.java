package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.world.phys.Vec3;

// A wall the player pushes into is climbed and a risen ceiling can be hung from.
// Both keep a block within reach to dodge the vanilla flight kick.
public final class Spider extends Module {

    // How far the box is probed upward for a ceiling to hang from.
    private static final double CEILING_PROBE = 0.05;

    // Pace along a ceiling in blocks a tick. About walking speed.
    private static final double CEILING_PACE = 0.2;

    private final NumberSetting speed = new NumberSetting("Speed",
        "How fast you go up the wall in blocks a tick.",
        0.2, 0.1, 0.5, 0.05, " blocks").max(1);
    private final BoolSetting ceilings = new BoolSetting("Ceilings",
        "Hang from a ceiling you climb or jump into and walk along it. Sneak to drop.", false);

    private boolean hanging;

    public Spider() {
        super("Spider", "Climb up any wall like a spider.", Category.MOVEMENT);
        addSettings(speed, ceilings);
        searchTags("wall climb", "ceiling");
    }

    @Override
    public String getSuffix() {
        return hanging ? "hanging" : null;
    }

    @Override
    protected void onDisable() {
        hanging = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        hanging = false;
        if (!inGame() || mc.player.isPassenger()) {
            return;
        }
        if (ceilings.isOn() && hang()) {
            return;
        }
        if (!mc.player.horizontalCollision) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        // Faster upward motion from jumps and boosts is left alone.
        if (velocity.y >= 0.2) {
            return;
        }
        mc.player.setDeltaMovement(velocity.x, speed.getValue(), velocity.z);
    }

    // Keeps pushing up into a ceiling. The collision holds the player against it.
    // The keys then move the player along it at walking pace.
    private boolean hang() {
        if (mc.player.onGround() || mc.player.isShiftKeyDown() || mc.player.isInWater()
            || mc.player.isInLava() || !ceilingAbove()) {
            return false;
        }
        hanging = true;
        Vec3 heading = MovementUtil.inputDirection();
        mc.player.setDeltaMovement(heading.x * CEILING_PACE, speed.getValue(), heading.z * CEILING_PACE);
        return true;
    }

    private boolean ceilingAbove() {
        return !mc.level.noCollision(mc.player,
            mc.player.getBoundingBox().move(0, CEILING_PROBE, 0));
    }
}
