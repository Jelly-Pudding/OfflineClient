package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

// Dodges the vanilla flight kick. The server counts the ticks a player hangs in the
// air without dropping and one dip of a few hundredths resets it.
public final class HoverDip {

    private static final double DIP = 0.04;

    private int ticks;

    public void reset() {
        ticks = 0;
    }

    public void tick(int interval) {
        tick(interval, 1);
    }

    // Runs every tick whilst hovering. Sinks for the dip ticks then climbs back
    // over as many so the height is unchanged once the dip is over.
    public void tick(int interval, int dipTicks) {
        if (ticks >= Math.max(interval, dipTicks * 2)) {
            ticks = 0;
        }
        double nudge = 0;
        if (ticks < dipTicks) {
            nudge = -DIP;
        } else if (ticks < dipTicks * 2) {
            nudge = DIP;
        }
        if (nudge != 0) {
            LocalPlayer player = OfflineClient.MC.player;
            Vec3 velocity = player.getDeltaMovement();
            player.setDeltaMovement(velocity.add(0, nudge, 0));
        }
        ticks++;
    }
}
