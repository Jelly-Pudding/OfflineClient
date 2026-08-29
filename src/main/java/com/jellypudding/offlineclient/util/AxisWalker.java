package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Holds the line and the floor a digging module works along. Also keeps the
 * player on the middle of that line. The line runs along a compass direction
 * or on request along a diagonal between two of them.
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

    // Unit vectors along the line and to its right. Diagonals are not whole.
    private double alongX;
    private double alongZ;
    private double rightX;
    private double rightZ;
    private boolean diagonal;

    public void lock() {
        lock(false);
    }

    // Takes the line from where the player faces snapped to the nearest eighth turn when asked.
    public void lock(boolean allowDiagonal) {
        axis = MC.player.getDirection();
        right = axis.getClockWise();
        origin = MC.player.blockPosition();
        floorY = origin.getY();

        float step = allowDiagonal ? 45f : 90f;
        float yaw = Math.round(Mth.wrapDegrees(MC.player.getYRot()) / step) * step;
        double radians = Math.toRadians(yaw);
        alongX = round(-Math.sin(radians));
        alongZ = round(Math.cos(radians));
        rightX = -alongZ;
        rightZ = alongX;
        diagonal = alongX != 0 && alongZ != 0;
    }

    // Kills the noise a sine leaves on a clean angle.
    private static double round(double value) {
        return Math.abs(value) < 1.0E-6 ? 0 : value;
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

    public boolean isDiagonal() {
        return diagonal;
    }

    // Reads as north east for a diagonal and north for a straight line.
    public String heading() {
        if (!diagonal) {
            return axis.getName();
        }
        String northSouth = alongZ < 0 ? "north" : "south";
        String eastWest = alongX > 0 ? "east" : "west";
        return northSouth + " " + eastWest;
    }

    public int floorY() {
        return floorY;
    }

    public Direction right() {
        return right;
    }

    // Keeps the view and the footing on the line the work started on.
    public void holdAxis() {
        holdAxis(true);
    }

    // Keeps the footing on the line. The view follows the line only when asked.
    public void holdAxis(boolean steerView) {
        if (axis == null || MC.player == null) {
            return;
        }
        if (steerView) {
            MC.player.setYRot((float) Math.toDegrees(Math.atan2(-alongX, alongZ)));
        }
        double offset = acrossOf(MC.player.getX(), MC.player.getZ());
        if (Math.abs(offset) < 0.05) {
            return;
        }
        double push = Math.clamp(-offset * CENTRE_PULL, -MAX_CENTRE_PUSH, MAX_CENTRE_PUSH);
        Vec3 velocity = MC.player.getDeltaMovement();
        MC.player.setDeltaMovement(velocity.x + rightX * push, velocity.y, velocity.z + rightZ * push);
    }

    // Walks along the line at a walking pace whatever way the player faces.
    public void walkAlong(double speed) {
        if (axis == null || MC.player == null) {
            return;
        }
        Vec3 velocity = MC.player.getDeltaMovement();
        MC.player.setDeltaMovement(alongX * speed, velocity.y, alongZ * speed);
    }

    // Blocks moved along the line since it was taken. Zero when unlocked.
    public double travelled() {
        if (axis == null || MC.player == null) {
            return 0;
        }
        return alongOf(MC.player.getX(), MC.player.getZ());
    }

    // Distance along the line from the origin to a point.
    public double alongOf(double x, double z) {
        return (x - origin.getX() - 0.5) * alongX + (z - origin.getZ() - 0.5) * alongZ;
    }

    // Distance to the right of the line to a point.
    public double acrossOf(double x, double z) {
        return (x - origin.getX() - 0.5) * rightX + (z - origin.getZ() - 0.5) * rightZ;
    }

    // Measured to the middle of the block.
    public double alongOf(BlockPos pos) {
        return alongOf(pos.getX() + 0.5, pos.getZ() + 0.5);
    }

    public double acrossOf(BlockPos pos) {
        return acrossOf(pos.getX() + 0.5, pos.getZ() + 0.5);
    }

    // Blocks along the line from the origin to a position on a straight line.
    public int depthOf(BlockPos pos) {
        return (int) Math.round(alongOf(pos));
    }

    // Blocks to the right of the line a position sits on a straight line.
    public int laneOf(BlockPos pos) {
        return (int) Math.round(acrossOf(pos));
    }

    // Blocks ahead then to the right then up. Whole blocks only on a straight line.
    public BlockPos blockAt(int depth, int lane, int up) {
        return new BlockPos(
            origin.getX() + (int) Math.round(alongX * depth + rightX * lane),
            floorY + up,
            origin.getZ() + (int) Math.round(alongZ * depth + rightZ * lane));
    }

    // Where the line is at a depth. For the outline of a diagonal.
    public Vec3 pointAt(double depth, double lane, double up) {
        return new Vec3(origin.getX() + 0.5 + alongX * depth + rightX * lane,
            floorY + up,
            origin.getZ() + 0.5 + alongZ * depth + rightZ * lane);
    }
}
