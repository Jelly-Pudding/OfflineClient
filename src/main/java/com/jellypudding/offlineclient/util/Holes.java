package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

public final class Holes {

    private static final Minecraft MC = OfflineClient.MC;

    // Feet resting on a block top sit this close to it.
    private static final double STANDING_GAP = 1.0E-3;
    // A body pressed against a wall ends exactly on the edge of the block it stands in.
    private static final double BODY_SLIVER = 1.0E-5;

    private Holes() {
    }

    public enum Wall {
        OPEN, WEAK, BLAST_PROOF, UNBREAKABLE;

        public boolean holds() {
            return this == BLAST_PROOF || this == UNBREAKABLE;
        }
    }

    public static Wall wallOf(BlockState state) {
        if (!BlockUtil.blocksMotion(state)) {
            return Wall.OPEN;
        }
        if (state.getBlock().defaultDestroyTime() < 0) {
            return Wall.UNBREAKABLE;
        }
        return BlockUtil.isBlastProof(state) ? Wall.BLAST_PROOF : Wall.WEAK;
    }

    public static boolean playerInHole() {
        return inHole(MC.player.blockPosition());
    }

    // A two block hole counts when its second block is open and walled on its own.
    public static boolean inHole(BlockPos feet) {
        return !hole(feet, BlockUtil::isBlastProof, Predicate.not(BlockUtil::isSolid), true).isEmpty();
    }

    // The blocks of the hole that starts at the given block. Each has a wall under it and walls
    // on every side but the one the two share. With two wide holes allowed one side may open
    // onto a second block that passes the open test. Empty when there is no hole. Whether the
    // first block is open is for the caller to ask.
    public static List<BlockPos> hole(BlockPos first, Predicate<BlockPos> wall, Predicate<BlockPos> open,
                                      boolean twoWide) {
        if (!wall.test(first.below())) {
            return List.of();
        }
        BlockPos second = null;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos next = first.relative(side);
            if (wall.test(next)) {
                continue;
            }
            if (!twoWide || second != null || !open.test(next) || !wall.test(next.below())
                || !walledBut(next, side.getOpposite(), wall)) {
                return List.of();
            }
            second = next;
        }
        return second == null ? List.of(first) : List.of(first, second);
    }

    private static boolean walledBut(BlockPos pos, Direction shared, Predicate<BlockPos> wall) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (side != shared && !wall.test(pos.relative(side))) {
                return false;
            }
        }
        return true;
    }

    // The blocks a body stands in at the level of its feet. One in the middle of a block and up
    // to four across an edge or a corner.
    public static List<BlockPos> feetBlocks(Entity entity) {
        AABB box = entity.getBoundingBox().deflate(BODY_SLIVER);
        int y = entity.blockPosition().getY();
        List<BlockPos> blocks = new ArrayList<>();
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                blocks.add(new BlockPos(x, y, z));
            }
        }
        return blocks;
    }

    // Someone crawling has the head at the level of the feet.
    public static BlockPos headBlock(Entity entity) {
        return BlockPos.containing(entity.getX(), entity.getBoundingBox().maxY, entity.getZ());
    }

    public static List<BlockPos> ringAround(Collection<BlockPos> blocks) {
        List<BlockPos> ring = new ArrayList<>();
        for (BlockPos block : blocks) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = block.relative(side);
                if (!blocks.contains(next) && !ring.contains(next)) {
                    ring.add(next);
                }
            }
        }
        return ring;
    }

    // Standing on top of a low block such as an ender chest is no burrow. The block has to reach
    // above the feet.
    public static boolean burrowed(Entity entity) {
        BlockPos feet = entity.blockPosition();
        BlockState state = BlockUtil.state(feet);
        if (!wallOf(state).holds()) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(MC.level, feet);
        return !shape.isEmpty() && feet.getY() + shape.max(Direction.Axis.Y) > entity.getY() + STANDING_GAP;
    }

    public static List<BlockPos> trapSpots(List<BlockPos> feet, boolean top, boolean sides, boolean bottom) {
        List<BlockPos> spots = new ArrayList<>();
        if (top) {
            for (BlockPos block : feet) {
                addOpen(spots, block.above(2));
            }
        }
        if (sides) {
            for (BlockPos side : ringAround(feet)) {
                addOpen(spots, side.above());
            }
        }
        if (bottom) {
            for (BlockPos block : feet) {
                addOpen(spots, block.below());
            }
        }
        return spots;
    }

    private static void addOpen(List<BlockPos> spots, BlockPos pos) {
        if (BlockUtil.blockFits(pos)) {
            spots.add(pos);
        }
    }

    public static BlockPos cityBlock(Player target, double reach) {
        return cityBlock(target.blockPosition(), reach);
    }

    public static BlockPos cityBlock(BlockPos centre, double reach) {
        BlockPos best = null;
        double bestDistance = reach;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos pos = centre.relative(side);
            BlockState state = BlockUtil.state(pos);
            if (state.isAir() || !BlockUtil.isBreakable(pos) || !BlockUtil.isBlastProof(state)) {
                continue;
            }
            double distance = BlockUtil.distanceTo(pos);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }
}
