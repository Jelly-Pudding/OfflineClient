package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// Holds the line and floor a digging module works along and keeps the player on a lane of it.
// The line runs along a compass direction or a diagonal between two of them.
public final class AxisWalker {

    private static final Minecraft MC = OfflineClient.MC;

    // Standing further than this above or below the floor counts as having left it.
    private static final double OFF_FLOOR = 1.5;

    // How hard a tick pushes the player back onto the held lane.
    private static final double CENTRE_PULL = 0.2;
    private static final double MAX_CENTRE_PUSH = 0.1;

    // The push stops once the player is this close to the lane.
    public static final double ON_LANE = 0.05;

    // A block no further ahead than this is beside you. Walking on brings it no nearer.
    public static final double BESIDE = 0.5;

    // The way the line runs. Null whilst no line is held.
    private CompassPoint heading;
    private BlockPos origin;
    private int floorY;

    // Unit vectors along the line and to its right. Diagonals are not whole.
    private double alongX;
    private double alongZ;
    private double rightX;
    private double rightZ;
    private boolean diagonal;

    // The lane the player is held on. Zero is the line and a half is the seam between two lanes.
    private double heldLane;

    public void lock() {
        lock(false);
    }

    // Takes the line from where the player faces snapped to the nearest eighth turn when asked.
    public void lock(boolean allowDiagonal) {
        origin = MC.player.blockPosition();
        floorY = origin.getY();

        float step = allowDiagonal ? 45f : 90f;
        float yaw = Math.round(Mth.wrapDegrees(MC.player.getYRot()) / step) * step;
        heading = CompassPoint.nearest(yaw);
        double radians = Math.toRadians(yaw);
        alongX = round(-Math.sin(radians));
        alongZ = round(Math.cos(radians));
        rightX = -alongZ;
        rightZ = alongX;
        diagonal = alongX != 0 && alongZ != 0;
        heldLane = 0;
    }

    // Kills the noise a sine leaves on a clean angle.
    private static double round(double value) {
        return Math.abs(value) < 1.0E-6 ? 0 : value;
    }

    public void clear() {
        heading = null;
        heldLane = 0;
    }

    public boolean isLocked() {
        return heading != null;
    }

    public boolean isDiagonal() {
        return diagonal;
    }

    // Reads as north east for a diagonal and north for a straight line.
    public String heading() {
        return heading.words();
    }

    public int floorY() {
        return floorY;
    }

    public boolean offFloor() {
        return offFloor(floorY);
    }

    // For a line whose floor changes height along the way.
    public boolean offFloor(int floor) {
        return Math.abs(MC.player.getY() - floor) > OFF_FLOOR;
    }

    // Lanes run from left to right with lane zero on the line. An even width leans right.
    public static int leftLane(int width) {
        return -((width - 1) / 2);
    }

    public static int rightLane(int width) {
        return width - 1 + leftLane(width);
    }

    // Keeps the view and the footing on the line the work started on.
    public void holdAxis() {
        holdAxis(true);
    }

    public void holdLane(double lane) {
        heldLane = lane;
    }

    // Keeps the footing on the held lane. The view follows the line only when asked.
    public void holdAxis(boolean steerView) {
        if (heading == null || MC.player == null) {
            return;
        }
        if (steerView) {
            MC.player.setYRot((float) Math.toDegrees(Math.atan2(-alongX, alongZ)));
        }
        if (onLane(heldLane)) {
            return;
        }
        double offset = acrossOf(MC.player.getX(), MC.player.getZ()) - heldLane;
        double push = Math.clamp(-offset * CENTRE_PULL, -MAX_CENTRE_PUSH, MAX_CENTRE_PUSH);
        Vec3 velocity = MC.player.getDeltaMovement();
        MC.player.setDeltaMovement(velocity.x + rightX * push, velocity.y, velocity.z + rightZ * push);
    }

    // True once the player stands close enough to the lane for the pull to stop.
    public boolean onLane(double lane) {
        return Math.abs(acrossOf(MC.player.getX(), MC.player.getZ()) - lane) < ON_LANE;
    }

    // Walks along the line whatever way the player faces. The sideways part of the motion is
    // kept. It holds the player on the lane.
    public void walkAlong(double speed) {
        if (heading == null || MC.player == null) {
            return;
        }
        Vec3 velocity = MC.player.getDeltaMovement();
        double across = velocity.x * rightX + velocity.z * rightZ;
        MC.player.setDeltaMovement(alongX * speed + rightX * across, velocity.y,
            alongZ * speed + rightZ * across);
    }

    // Blocks moved along the line since it was taken. Zero when unlocked.
    public double travelled() {
        if (heading == null || MC.player == null) {
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
        return depthOf(pos.getX() + 0.5, pos.getZ() + 0.5);
    }

    public int depthOf(double x, double z) {
        return (int) Math.round(alongOf(x, z));
    }

    // Blocks ahead then to the right then up. Whole blocks only on a straight line.
    public BlockPos blockAt(int depth, int lane, int up) {
        return new BlockPos(
            origin.getX() + (int) Math.round(alongX * depth + rightX * lane),
            floorY + up,
            origin.getZ() + (int) Math.round(alongZ * depth + rightZ * lane));
    }

    // A point at a depth along the line. The outline of a diagonal is drawn from these.
    public Vec3 pointAt(double depth, double lane, double up) {
        return new Vec3(origin.getX() + 0.5 + alongX * depth + rightX * lane,
            floorY + up,
            origin.getZ() + 0.5 + alongZ * depth + rightZ * lane);
    }

    // Where the eyes of a player standing on a lane at a point along the line would be.
    public Vec3 eyeAt(double along, double lane, int floor) {
        Vec3 spot = pointAt(along, lane, 0);
        return new Vec3(spot.x, floor + MC.player.getEyeHeight(), spot.z);
    }

    // True when a player could stand on a lane at a point along the line. The floor holds them
    // and the two blocks above it let them in unharmed.
    public boolean standable(double along, double lane, int floor) {
        BlockPos feet = BlockPos.containing(pointAt(along, lane, 0)).atY(floor);
        return BlockUtil.isSolid(feet.below()) && passable(feet) && passable(feet.above());
    }

    private static boolean passable(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        return !BlockUtil.blocksMotion(state) && !BlockUtil.harmful(state) && state.getFluidState().isEmpty();
    }
}
