package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

// The move along the ground a module sets as the whole move of one tick. LivingEntityMixin
// leaves the push of the movement keys off it.
public final class WholeMove {

    private static int tick = Integer.MIN_VALUE;
    private static double moveX;
    private static double moveZ;

    private WholeMove() {
    }

    // The height is left alone.
    public static void set(double x, double z) {
        LocalPlayer player = OfflineClient.MC.player;
        player.setDeltaMovement(x, player.getDeltaMovement().y, z);
        tick = player.tickCount;
        moveX = x;
        moveZ = z;
    }

    // True whilst the speed along the ground is still the move set this tick. A pace another
    // module sets later in the tick keeps the push of the keys.
    public static boolean holds(Entity mover) {
        Vec3 velocity = mover.getDeltaMovement();
        return mover.tickCount == tick && velocity.x == moveX && velocity.z == moveZ;
    }
}
