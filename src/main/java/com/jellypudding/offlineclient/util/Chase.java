package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.path.PathWalker;
import com.jellypudding.offlineclient.path.Trip;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

// Keeps the player on the heels of an entity. Faces it and either walks straight
// at it or lets the pathfinder pick the way round whatever is between.
public final class Chase {

    // A moving target needs a fresh path this often.
    private static final int RETARGET_TICKS = 10;

    // The nudge that lifts a swimmer up towards a target above the water.
    private static final double SWIM_LIFT = 0.04;

    private final Trip trip = new Trip();
    private int retarget;
    private boolean holding;

    public void tick(Entity target, double keep, boolean usePath) {
        LocalPlayer player = OfflineClient.MC.player;
        FaceMode.CLIENT.face(target.getBoundingBox().getCenter(), RotationPriority.ATTACK);
        if (usePath) {
            letGo();
            if (--retarget <= 0 || !trip.active()) {
                retarget = RETARGET_TICKS;
                trip.walker().turn(PathWalker.Turn.NONE);
                trip.start(target.blockPosition(), keep);
            }
            trip.tick();
            return;
        }
        trip.stop();
        if (player.horizontalCollision && player.onGround()) {
            player.jumpFromGround();
        }
        if (player.isInWater() && player.getY() < target.getY()) {
            player.push(0, SWIM_LIFT, 0);
        }
        if (player.distanceTo(target) > keep) {
            InputUtil.hold(OfflineClient.MC.options.keyUp);
            holding = true;
        } else {
            letGo();
        }
    }

    public void stop() {
        trip.stop();
        letGo();
    }

    private void letGo() {
        if (holding) {
            InputUtil.release(OfflineClient.MC.options.keyUp);
            holding = false;
        }
    }
}
