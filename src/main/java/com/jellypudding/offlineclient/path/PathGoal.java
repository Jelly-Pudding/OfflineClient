package com.jellypudding.offlineclient.path;

import net.minecraft.core.BlockPos;

// Where a search is trying to get to. The heuristic is measured in blocks and
// must never read higher than the real walk or the search stops finding the
// shortest way round.
public interface PathGoal {

    boolean reached(BlockPos pos);

    double heuristic(BlockPos pos);

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
        public double heuristic(BlockPos pos) {
            return estimate(pos, target);
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
        public double heuristic(BlockPos pos) {
            return Math.max(0, estimate(pos, target) - radius);
        }
    }

    // The straight line with any height below counted at the cost of falling it.
    static double estimate(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dy = to.getY() - from.getY();
        double dz = from.getZ() - to.getZ();
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
