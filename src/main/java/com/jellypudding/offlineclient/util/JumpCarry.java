package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

// Holds one horizontal speed for the length of a jump. The heading follows
// the movement keys and stays where it was when they are let go.
public final class JumpCarry {

    private double speed;
    private double headingX;
    private double headingZ;
    private boolean hasHeading;

    // Cleared from the packet thread when the server sends the player back.
    private volatile boolean active;

    public boolean isActive() {
        return active;
    }

    // Starts holding the speed. Nothing happens without a key to take the heading from.
    public void start(double speed) {
        if (!steer()) {
            return;
        }
        this.speed = speed;
        active = true;
        push();
    }

    // Keeps the speed up for one more tick.
    // Lets go on landing or anywhere that is not plain air.
    public void tick() {
        if (!active) {
            return;
        }
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || player.onGround() || !inPlainAir(player)) {
            stop();
            return;
        }
        steer();
        push();
    }

    public void stop() {
        active = false;
        hasHeading = false;
    }

    // True whilst nothing but gravity is acting on the player.
    public static boolean inPlainAir(LocalPlayer player) {
        return !player.isSpectator() && !player.isPassenger()
            && !player.getAbilities().flying && !player.isFallFlying()
            && !player.isInWater() && !player.isInLava() && !player.onClimbable();
    }

    // Takes the heading from the keys. False when there is none to take and none kept.
    private boolean steer() {
        Vec3 input = MovementUtil.inputDirection();
        if (input.lengthSqr() == 0) {
            return hasHeading;
        }
        headingX = input.x;
        headingZ = input.z;
        hasHeading = true;
        return true;
    }

    private void push() {
        LocalPlayer player = OfflineClient.MC.player;
        Vec3 velocity = player.getDeltaMovement();
        player.setDeltaMovement(headingX * speed, velocity.y, headingZ * speed);
    }
}
