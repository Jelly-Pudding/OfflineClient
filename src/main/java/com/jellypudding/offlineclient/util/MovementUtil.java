package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public final class MovementUtil {

    private MovementUtil() {
    }

    /**
     * The way the movement keys point in world space. Vec3.ZERO whilst none
     * of them is held.
     */
    public static Vec3 inputDirection() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return Vec3.ZERO;
        }
        Vec2 move = player.input.getMoveVector();
        if (move.x == 0 && move.y == 0) {
            return Vec3.ZERO;
        }
        double yaw = Math.toRadians(player.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        // The x of the move vector strafes. The y of it drives forward.
        return new Vec3(move.x * cos - move.y * sin, 0, move.y * cos + move.x * sin).normalize();
    }
}
