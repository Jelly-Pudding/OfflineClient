package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Brakes a flight before it runs into a block. The body is swept along the flight
// path a tick at a time and the speed is cut ahead of the first tick that would hit.
public final class CrashGuard {

    // Ticks of travel swept ahead at the least and at the most.
    private static final double MIN_TICKS = 4;
    private static final int MAX_TICKS = 40;

    // Share of the safe speed kept after a brake. Leaves room to add some back.
    private static final double KEEP = 0.6;

    // The velocity the last brake set. Two modules guarding one flight brake it once.
    private static Vec3 braked;

    private final BoolSetting enabled = new BoolSetting("No crash",
        "Brakes before you fly into a wall or the ground. The faster you go the further ahead it looks.",
        false);
    private final NumberSetting lookAhead = new NumberSetting("Crash look ahead",
        "The least distance along your flight path to check.", 5, 1, 15, 1, " blocks").min(1)
        .under(enabled);

    // The settings in the order a module should show them.
    public Setting<?>[] settings() {
        return new Setting<?>[] {enabled, lookAhead};
    }

    public void brake(LocalPlayer player) {
        Vec3 velocity = player.getDeltaMovement();
        double pace = velocity.length();
        if (!enabled.isOn() || velocity == braked || pace < 0.01) {
            return;
        }
        int ticks = Math.min((int) Math.ceil(Math.max(lookAhead.getValue(), pace * MIN_TICKS) / pace),
            MAX_TICKS);
        AABB box = player.getBoundingBox();
        for (int step = 1; step <= ticks; step++) {
            if (player.level().noCollision(player, box.move(velocity.scale(step)))) {
                continue;
            }
            // A hit two ticks out leaves no room to slow down gently.
            double allowed = step <= 2 ? 0 : pace * (step - 1) / ticks * KEEP;
            if (pace > allowed) {
                braked = velocity.scale(allowed / pace);
                player.setDeltaMovement(braked);
            }
            return;
        }
    }
}
