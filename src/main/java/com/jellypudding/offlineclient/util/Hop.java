package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Moves the player in a single packet. The server checks the move without its
// height and a straight rise or drop can pass through blocks. The landing spot
// has to be clear. The server charges a drop as a fall once a packet claims
// ground. Nothing claims it whilst a fall is owed and the next free tick rises a
// hair. That rise wipes the fall before the player settles onto the ground.
public enum Hop {
    INSTANCE;

    // Three fillers buy the position packet sqrt(400) blocks of travel. A hair is
    // kept in hand.
    public static final double REACH = 19.9;
    private static final int FILLERS = 3;

    private static final double WIPE_RISE = 0.02;

    // How far under a spot the ground may sit and still count as standing on it.
    private static final double GROUND_PROBE = 0.05;

    public enum Result {
        MOVED(""),
        TOO_FAR("That is more than " + REACH + " blocks away."),
        BLOCKED("That spot is inside a block."),
        BUSY("Try again in a moment.");

        private final String problem;

        Result(String problem) {
            this.problem = problem;
        }

        public String problem() {
            return problem;
        }
    }

    // Where the last hop down landed until the fall it left is wiped.
    private static Vec3 owed;
    private static boolean rising;

    public static Result to(Vec3 spot) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || !MoveGate.free()) {
            return Result.BUSY;
        }
        if (player.position().distanceTo(spot) > REACH) {
            return Result.TOO_FAR;
        }
        if (!fits(player, spot)) {
            return Result.BLOCKED;
        }
        boolean up = spot.y > player.getY();
        boolean down = spot.y < player.getY();
        boolean ground = !down && owed == null && standingAt(player, spot);
        MoveGate.fillers(FILLERS, ground);
        if (!MoveGate.send(spot.x, spot.y, spot.z, ground)) {
            return Result.BUSY;
        }
        // A rise wipes whatever fall the server still held.
        if (up) {
            owed = null;
        } else if (down || owed != null) {
            owed = spot;
        }
        rising = false;
        player.setPos(spot);
        // The rest of the tick still runs the physics. Gravity between hops would
        // otherwise gather into a fall.
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        return Result.MOVED;
    }

    // True when the whole box has room at the spot.
    public static boolean fits(LocalPlayer player, Vec3 spot) {
        return player.level().noCollision(player, boxAt(player, spot));
    }

    private static boolean standingAt(LocalPlayer player, Vec3 spot) {
        return !player.level().noCollision(player, boxAt(player, spot).move(0, -GROUND_PROBE, 0));
    }

    private static AABB boxAt(LocalPlayer player, Vec3 spot) {
        return player.getBoundingBox().move(spot.subtract(player.position()));
    }

    // Waits for a tick with its position packet free. That packet holds the player a
    // hair above the landing and the one after lets them settle.
    @Subscribe
    private void onTick(TickEvent event) {
        LocalPlayer player = OfflineClient.MC.player;
        // A respawn or a new dimension leaves the landing spot behind.
        if (owed == null || player == null || player.position().distanceTo(owed) > REACH) {
            owed = null;
            rising = false;
            return;
        }
        if (rising) {
            owed = null;
            rising = false;
            return;
        }
        if (!MoveGate.free()) {
            return;
        }
        player.setPos(owed.x, owed.y + WIPE_RISE, owed.z);
        player.setDeltaMovement(Vec3.ZERO);
        rising = true;
    }
}
