package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Rig;
import net.minecraft.world.phys.Vec3;

// Moves the test player with held keys and says how its server saw it go. Where the player
// ends up is asked of the server. The way there is watched tick by tick in the game itself.
final class Motion {

    // Ticks the server gets to hear the last moves before it is asked where the player is.
    private static final int HEARD_TICKS = 4;
    private static final int LAND_LIMIT = 300;

    // How one stretch of movement went. Heights are counted from where it began.
    record Moved(double across, double rise, double highest, double lowest, int airTicks, int takeOffs,
                 int setbacks) {
    }

    private Motion() {
    }

    // Holds the keys for the ticks and lets go of them again.
    static Moved hold(Rig rig, int ticks, int... keys) {
        return watch(rig, ticks, () -> {
            for (int key : keys) {
                rig.controls().hold(key);
            }
        }, () -> {
            for (int key : keys) {
                rig.controls().release(key);
            }
        });
    }

    // Watches the player for the ticks between two things a case does.
    static Moved watch(Rig rig, int ticks, Runnable begin, Runnable end) {
        Vec3 from = rig.world().where();
        int setbacks = rig.setbacks();
        double highest = from.y;
        double lowest = from.y;
        int airTicks = 0;
        int takeOffs = 0;
        boolean grounded = rig.ask(mc -> mc.player.onGround());
        begin.run();
        try {
            for (int tick = 0; tick < ticks; tick++) {
                rig.ticks(1);
                double y = rig.ask(mc -> mc.player.getY());
                boolean onGround = rig.ask(mc -> mc.player.onGround());
                highest = Math.max(highest, y);
                lowest = Math.min(lowest, y);
                airTicks += onGround ? 0 : 1;
                takeOffs += grounded && !onGround ? 1 : 0;
                grounded = onGround;
            }
        } finally {
            end.run();
        }
        rig.ticks(HEARD_TICKS);
        Vec3 to = rig.world().where();
        return new Moved(to.subtract(from).horizontalDistance(), to.y - from.y, highest - from.y, lowest - from.y,
            airTicks, takeOffs, rig.setbacks() - setbacks);
    }

    // Ticks until the player stands on something or floats in water. The fall begins here.
    static int untilLanded(Rig rig) {
        rig.until("the fall to begin", HEARD_TICKS * 5, mc -> !mc.player.onGround());
        return rig.until("the landing", LAND_LIMIT, mc -> mc.player.onGround() || mc.player.isInWater());
    }
}
