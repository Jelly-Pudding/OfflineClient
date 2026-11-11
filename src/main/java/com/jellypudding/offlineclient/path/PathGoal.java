package com.jellypudding.offlineclient.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// Where a search is trying to get to. The heuristic is measured in blocks and
// must never read higher than the real walk or the search stops finding the
// shortest way round.
public interface PathGoal {

    boolean reached(BlockPos pos);

    // The walk lifts the player up to stepUp blocks in one step without a jump.
    double heuristic(BlockPos pos, int stepUp);

    // Blocks from a place to the goal in a straight line.
    double distance(Vec3 from);

    // One exact block.
    record Spot(BlockPos target) implements PathGoal {

        public Spot {
            target = target.immutable();
        }

        @Override
        public boolean reached(BlockPos pos) {
            return pos.equals(target);
        }

        @Override
        public double heuristic(BlockPos pos, int stepUp) {
            return estimate(pos, target, stepUp);
        }

        @Override
        public double distance(Vec3 from) {
            return from.distanceTo(Vec3.atBottomCenterOf(target));
        }
    }

    // Anywhere within the radius of a block.
    record Around(BlockPos target, double radius) implements PathGoal {

        public Around {
            target = target.immutable();
        }

        @Override
        public boolean reached(BlockPos pos) {
            return straightLine(pos, target) <= radius;
        }

        @Override
        public double heuristic(BlockPos pos, int stepUp) {
            return Math.max(0, estimate(pos, target, stepUp) - radius);
        }

        @Override
        public double distance(Vec3 from) {
            return from.distanceTo(Vec3.atBottomCenterOf(target));
        }
    }

    // A place on the map at whatever height the walk comes to it.
    record Column(int x, int z) implements PathGoal {

        @Override
        public boolean reached(BlockPos pos) {
            return pos.getX() == x && pos.getZ() == z;
        }

        @Override
        public double heuristic(BlockPos pos, int stepUp) {
            return Math.hypot(pos.getX() - x, pos.getZ() - z);
        }

        @Override
        public double distance(Vec3 from) {
            return Math.hypot(from.x - (x + 0.5), from.z - (z + 0.5));
        }
    }

    // The straight line with any height below counted at the cost of falling it.
    static double estimate(BlockPos from, BlockPos to, int stepUp) {
        double dx = from.getX() - to.getX();
        double dy = to.getY() - from.getY();
        double dz = from.getZ() - to.getZ();
        // A step costs a block at least and lifts the player stepUp blocks at most. Neither the
        // level distance nor the climb counted at that rate can read high.
        if (dy > 0 && stepUp > 0) {
            return Math.max(Math.sqrt(dx * dx + dz * dz), dy / stepUp);
        }
        if (dy < 0) {
            dy *= PathFinder.FALL;
        }
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static double straightLine(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dy = from.getY() - to.getY();
        double dz = from.getZ() - to.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
