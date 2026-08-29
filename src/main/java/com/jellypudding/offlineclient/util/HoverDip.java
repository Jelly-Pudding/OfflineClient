package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Dodges the vanilla flight kick. The server counts the ticks a player hangs
 * in the air without dropping and one dip of a few hundredths resets it.
 */
public final class HoverDip {

    private static final double DIP = 0.04;

    private int ticks;

    public void reset() {
        ticks = 0;
    }

    // Runs every tick whilst hovering. Bends the motion on the two ticks of a dip.
    public void tick(int interval) {
        if (ticks >= interval) {
            ticks = 0;
        }
        double nudge = switch (ticks) {
            case 0 -> -DIP;
            case 1 -> DIP;
            default -> 0;
        };
        if (nudge != 0) {
            LocalPlayer player = OfflineClient.MC.player;
            Vec3 velocity = player.getDeltaMovement();
            player.setDeltaMovement(velocity.add(0, nudge, 0));
        }
        ticks++;
    }
}
