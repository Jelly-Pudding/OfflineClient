package com.jellypudding.offlineclient.util;

import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

// Reads which way a trail of old chunks runs from a place. Every way in a fan is scored by the
// chunks along it and ways that score well side by side make one branch. Where the fan holds two
// branches a side can be preferred.
public final class TrailScan {

    public enum Side { EITHER, LEFT, RIGHT }

    // What one chunk says about a way through it. Old chunks count for it and new ones against.
    @FunctionalInterface
    public interface Weights {
        double of(int chunkX, int chunkZ);
    }

    // Branches part where the ways between them score under this share of the best. A branch is
    // only taken with a way of its own scoring at least the larger share.
    private static final double PARTING_SHARE = 0.25;
    private static final double BRANCH_SHARE = 0.5;
    private static final int CHUNK = SectionPos.SECTION_SIZE;
    // The way is read every half chunk.
    private static final double READ_STEP = CHUNK / 2.0;

    private final Weights weights;
    private final Side side;
    // The heading the trail was found on and how far a way may turn from it.
    private final float firstYaw;
    private final float widest;

    public TrailScan(Weights weights, Side side, float firstYaw, float widest) {
        this.weights = weights;
        this.side = side;
        this.firstYaw = firstYaw;
        this.widest = widest;
    }

    // The heading down the middle of the branch to follow out of a fan of ways round the given
    // one. The ways are read from the near chunk to the far one. NaN when no way scores above the
    // floor.
    public float way(Vec3 from, float around, float spread, float step, int near, int far, double floor) {
        int count = (int) (spread / step);
        double[] scores = new double[2 * count + 1];
        int best = -1;
        for (int i = 0; i < scores.length; i++) {
            float yaw = around + (i - count) * step;
            scores[i] = turnsTooFar(yaw) ? Double.NEGATIVE_INFINITY : score(from, yaw, near, far);
            if (best == -1 || scores[i] > scores[best]
                || scores[i] == scores[best] && Math.abs(i - count) < Math.abs(best - count)) {
                best = i;
            }
        }
        if (scores[best] <= floor) {
            return Float.NaN;
        }
        int pick = branchMiddle(scores, side == Side.EITHER ? best : preferred(scores, best), scores[best]);
        return Mth.wrapDegrees(around + (pick - count) * step);
    }

    // Old chunks along the way count for it and new ones against it. Each chunk counts once.
    private double score(Vec3 from, float yaw, int near, int far) {
        Vec3 way = Vec3.directionFromRotation(0, yaw);
        double score = 0;
        long last = Long.MIN_VALUE;
        for (double along = near * CHUNK; along <= far * CHUNK; along += READ_STEP) {
            int chunkX = SectionPos.blockToSectionCoord(Mth.floor(from.x + way.x * along));
            int chunkZ = SectionPos.blockToSectionCoord(Mth.floor(from.z + way.z * along));
            long chunk = ChunkPos.pack(chunkX, chunkZ);
            if (chunk == last) {
                continue;
            }
            last = chunk;
            score += weights.of(chunkX, chunkZ);
        }
        return score;
    }

    private boolean turnsTooFar(float yaw) {
        return Math.abs(Mth.wrapDegrees(yaw - firstYaw)) > widest;
    }

    // A way inside the first branch from the preferred side that is strong enough to take.
    // Left turns to a lower yaw and starts the fan.
    private int preferred(double[] scores, int best) {
        double parting = scores[best] * PARTING_SHARE;
        double strong = scores[best] * BRANCH_SHARE;
        int direction = side == Side.LEFT ? 1 : -1;
        int i = side == Side.LEFT ? 0 : scores.length - 1;
        while (i >= 0 && i < scores.length) {
            if (scores[i] < parting) {
                i += direction;
                continue;
            }
            boolean strongEnough = false;
            int start = i;
            for (; i >= 0 && i < scores.length && scores[i] >= parting; i += direction) {
                strongEnough |= scores[i] >= strong;
            }
            if (strongEnough) {
                return start;
            }
        }
        return best;
    }

    // The way down the middle of the branch that holds the given one. Each way of the branch pulls
    // by how far it scores over the parting share. Chunk edges make the best scores of a wide trail
    // come and go across it and the middle holds steady.
    private static int branchMiddle(double[] scores, int inside, double best) {
        double parting = best * PARTING_SHARE;
        int low = inside;
        while (low > 0 && scores[low - 1] >= parting) {
            low--;
        }
        int high = inside;
        while (high < scores.length - 1 && scores[high + 1] >= parting) {
            high++;
        }
        double pull = 0;
        double weight = 0;
        for (int i = low; i <= high; i++) {
            pull += i * (scores[i] - parting);
            weight += scores[i] - parting;
        }
        return weight > 0 ? (int) Math.round(pull / weight) : (low + high) / 2;
    }
}
