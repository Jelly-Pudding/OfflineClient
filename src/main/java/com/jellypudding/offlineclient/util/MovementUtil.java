package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class MovementUtil {

    private MovementUtil() {
    }

    // Blocks per tick at a speed of one. Every flight module shares the pace.
    public static final double FLY_HORIZONTAL = 0.5;
    public static final double FLY_VERTICAL = 0.225;

    public static final float VANILLA_FLY_SPEED = 0.05f;

    // Clears a ledge top by a hair. The box lands on it rather than in it.
    public static final double LEDGE_CLEARANCE = 0.001;

    // How far past the box a ledge is looked for. The look starts just above the
    // floor underfoot to leave it out.
    private static final double LEDGE_REACH = 0.1;
    private static final double FLOOR_GAP = 0.05;

    // The way the movement keys point in world space.
    // Vec3.ZERO whilst none of them is held.
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

    // The space just ahead of the entity where a ledge it walks into would be.
    public static AABB ledgeProbe(Entity entity, Vec3 heading) {
        Vec3 ahead = heading.scale(LEDGE_REACH);
        return entity.getBoundingBox().move(ahead.x, FLOOR_GAP, ahead.z);
    }

    // How far up the ledge ahead sits. Zero when there is none or the space on top
    // is taken. A wall that carries on upward fails the room check.
    public static double ledgeRise(Entity entity, Vec3 heading) {
        Level level = entity.level();
        double top = Double.NEGATIVE_INFINITY;
        for (VoxelShape shape : level.getBlockCollisions(entity, ledgeProbe(entity, heading))) {
            top = Math.max(top, shape.bounds().maxY);
        }
        double rise = top - entity.getY();
        if (rise <= 0) {
            return 0;
        }
        AABB box = entity.getBoundingBox();
        Vec3 ahead = heading.scale(LEDGE_REACH);
        double lifted = rise + LEDGE_CLEARANCE;
        boolean fits = level.noCollision(entity, box.move(0, lifted, 0))
            && level.noCollision(entity, box.move(ahead.x, lifted, ahead.z));
        return fits ? rise : 0;
    }
}
