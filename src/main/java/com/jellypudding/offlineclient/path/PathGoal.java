package com.jellypudding.offlineclient.path;

import net.minecraft.core.BlockPos;

import java.util.List;

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
            return straightLine(pos, target);
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
            return Math.max(0, straightLine(pos, target) - radius);
        }
    }

    // Within the radius of any block on the list. An empty list is never reached.
    record AnyOf(List<BlockPos> targets, double radius) implements PathGoal {

        public AnyOf {
            targets = List.copyOf(targets);
        }

        @Override
        public boolean reached(BlockPos pos) {
            return nearest(pos) <= radius;
        }

        @Override
        public double heuristic(BlockPos pos) {
            return Math.max(0, nearest(pos) - radius);
        }

        private double nearest(BlockPos pos) {
            double best = Double.MAX_VALUE;
            for (BlockPos target : targets) {
                best = Math.min(best, straightLine(pos, target));
            }
            return best;
        }
    }

    static double straightLine(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dy = from.getY() - to.getY();
        double dz = from.getZ() - to.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
