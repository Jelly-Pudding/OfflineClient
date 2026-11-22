package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

// Dodges the vanilla flight kick. The server counts its own ticks for as long as the last
// move it heard from a player in open air did not drop and throws the player out at eighty.
// A drop of a few hundredths resets the count once one of those ticks comes round whilst the
// drop is the last thing the server heard. The server ticks by the clock. A game that stutters
// plays its ticks late and several of them in one frame. Both the gap between two dips and the
// length of one go by the clock for that reason.
public final class HoverDip {

    private static final double DIP = 0.04;
    // A dip half a tick longer than the server ticks it has to last cannot fall between them.
    private static final long SLACK_MILLIS = SharedConstants.MILLIS_PER_TICK / 2;

    private enum Part { REST, SINK, CLIMB, SETTLE }

    private Part part = Part.REST;
    private int sunk;
    private int owed;
    // Wall clock milliseconds at which the server's count last began again.
    private long dippedAt;

    // True where a server may throw the player out for floating. It counts nobody it lets fly
    // and a world of your own lets everybody.
    public static boolean counted() {
        Minecraft mc = OfflineClient.MC;
        return !mc.player.isCreative() && !mc.player.isSpectator() && !mc.hasSingleplayerServer();
    }

    public void reset() {
        part = Part.REST;
        sunk = 0;
        owed = 0;
        dippedAt = 0;
    }

    // True once the interval has passed on the server since the last dip began. A trip by
    // packet leaves the server's own count ahead of the clock and the dip comes early.
    public boolean due(int interval) {
        return sinceDip() >= (long) interval * SharedConstants.MILLIS_PER_TICK
            || MoveGate.floatingTicks(false) >= interval;
    }

    public void begin() {
        dippedAt = System.currentTimeMillis();
    }

    public boolean seen(int serverTicks) {
        return sinceDip() >= (long) serverTicks * SharedConstants.MILLIS_PER_TICK + SLACK_MILLIS;
    }

    private long sinceDip() {
        return System.currentTimeMillis() - dippedAt;
    }

    // True whilst this tick is part of the way down. Whatever lifts the player has to wait.
    public boolean sinking() {
        return part == Part.SINK;
    }

    // A whole dip for a player who hangs still. Runs every tick of the hover.
    public void tick(int interval) {
        if (counted()) {
            plan(interval, 1, true);
            apply();
        }
    }

    // Picks this tick's part of a dip. It sinks until the server has seen it and climbs back
    // over as many ticks as it sank and the height is then what it was. Only a player who
    // hangs still is owed the climb. One who rises or sinks by a key keeps what the key gave.
    public void plan(int interval, int dipTicks, boolean hangsStill) {
        // The count is nought for as long as the player stands on something. A dip there sinks
        // nowhere and its climb alone would lift the player off the ground.
        if (OfflineClient.MC.player.onGround()) {
            begin();
            part = Part.REST;
            return;
        }
        switch (part) {
            case REST -> {
                if (due(interval)) {
                    begin();
                    part = Part.SINK;
                    sunk = 0;
                }
            }
            case SINK -> {
                if (seen(dipTicks)) {
                    owed = sunk;
                    part = Part.CLIMB;
                }
            }
            case CLIMB -> {
                if (owed == 0) {
                    part = Part.SETTLE;
                }
            }
            case SETTLE -> part = Part.REST;
        }
        if (!hangsStill && (part == Part.CLIMB || part == Part.SETTLE)) {
            part = Part.REST;
        }
    }

    // Gives the player the speed this tick's part asks for. It goes after whatever else sets
    // the speed this tick. The tick after the climb stops what is left of it.
    public void apply() {
        LocalPlayer player = OfflineClient.MC.player;
        Vec3 speed = player.getDeltaMovement();
        switch (part) {
            case SINK -> {
                sunk++;
                player.setDeltaMovement(speed.x, Math.min(speed.y, -DIP), speed.z);
            }
            case CLIMB -> {
                owed--;
                player.setDeltaMovement(speed.x, DIP, speed.z);
            }
            case SETTLE -> player.setDeltaMovement(speed.x, 0, speed.z);
            case REST -> { }
        }
    }
}
