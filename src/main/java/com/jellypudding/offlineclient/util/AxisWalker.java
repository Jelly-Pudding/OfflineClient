package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Holds the direction and the floor a digging module works along. Also keeps
 * the player on the middle of that line.
 */
public final class AxisWalker {

    private static final Minecraft MC = OfflineClient.MC;

    // How hard a tick pushes the player back onto the middle line.
    private static final double CENTRE_PULL = 0.2;
    private static final double MAX_CENTRE_PUSH = 0.1;

    private Direction axis;
    private Direction right;
    private BlockPos origin;
    private int floorY;

    public void lock() {
        axis = MC.player.getDirection();
        right = axis.getClockWise();
        origin = MC.player.blockPosition();
        floorY = origin.getY();
    }

    public void clear() {
        axis = null;
    }

    public boolean isLocked() {
        return axis != null;
    }

    public Direction axis() {
        return axis;
    }

    public int floorY() {
        return floorY;
    }

    // Keeps the view and the footing on the line the work started on.
    public void holdAxis() {
        if (axis == null || MC.player == null) {
            return;
        }
        MC.player.setYRot(axis.toYRot());
        double offset = along(right, MC.player.getX(), MC.player.getZ()) - centreLine();
        if (Math.abs(offset) < 0.05) {
            return;
        }
        double push = Math.clamp(-offset * CENTRE_PULL, -MAX_CENTRE_PUSH, MAX_CENTRE_PUSH);
        Vec3 velocity = MC.player.getDeltaMovement();
        MC.player.setDeltaMovement(velocity.x + right.getStepX() * push, velocity.y,
            velocity.z + right.getStepZ() * push);
    }

    // Blocks moved along the line since it was taken. Zero when unlocked.
    public double travelled() {
        if (axis == null || MC.player == null) {
            return 0;
        }
        return along(axis, MC.player.getX(), MC.player.getZ())
            - along(axis, origin.getX() + 0.5, origin.getZ() + 0.5);
    }

    // Blocks ahead then to the right then up.
    public BlockPos blockAt(int depth, int lane, int up) {
        return new BlockPos(
            origin.getX() + axis.getStepX() * depth + right.getStepX() * lane,
            floorY + up,
            origin.getZ() + axis.getStepZ() * depth + right.getStepZ() * lane);
    }

    private double centreLine() {
        return along(right, origin.getX() + 0.5, origin.getZ() + 0.5);
    }

    private static double along(Direction direction, double x, double z) {
        return x * direction.getStepX() + z * direction.getStepZ();
    }
}
