package com.jellypudding.offlineclient.config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Objects;

// The build is mirrored and then turned about its anchor the way the game turns a structure.
public final class BuildFrame {

    // The way a build faces as it was saved. Someone looking south sees it the way it was made.
    private static final Direction SAVED_FRONT = Direction.SOUTH;

    private final BlockPos origin;
    private final Rotation rotation;
    private final Mirror mirror;
    private final Vec3i stepX;
    private final Vec3i stepZ;

    public BuildFrame(BlockPos origin, Rotation rotation, Mirror mirror) {
        this.origin = origin.immutable();
        this.rotation = rotation;
        this.mirror = mirror;
        this.stepX = StructureTemplate.transform(new BlockPos(1, 0, 0), mirror, rotation, BlockPos.ZERO);
        this.stepZ = StructureTemplate.transform(new BlockPos(0, 0, 1), mirror, rotation, BlockPos.ZERO);
    }

    public static BuildFrame at(BlockPos origin) {
        return new BuildFrame(origin, Rotation.NONE, Mirror.NONE);
    }

    public static BuildFrame facing(BlockPos origin, Direction front) {
        Rotation turn = Rotation.NONE;
        while (turn.rotate(SAVED_FRONT) != front) {
            turn = turn.getRotated(Rotation.CLOCKWISE_90);
        }
        return new BuildFrame(origin, turn, Mirror.NONE);
    }

    public BlockPos origin() {
        return origin;
    }

    public Rotation rotation() {
        return rotation;
    }

    public Mirror mirror() {
        return mirror;
    }

    public BuildFrame movedTo(BlockPos spot) {
        return new BuildFrame(spot, rotation, mirror);
    }

    public BuildFrame turned(boolean clockwise) {
        return new BuildFrame(origin,
            rotation.getRotated(clockwise ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90), mirror);
    }

    public BuildFrame mirrored() {
        return new BuildFrame(origin, rotation, mirror == Mirror.NONE ? Mirror.FRONT_BACK : Mirror.NONE);
    }

    public BlockPos toWorld(int x, int y, int z) {
        return new BlockPos(worldX(x, z), worldY(y), worldZ(x, z));
    }

    public int worldX(int x, int z) {
        return origin.getX() + x * stepX.getX() + z * stepZ.getX();
    }

    public int worldY(int y) {
        return origin.getY() + y;
    }

    public int worldZ(int x, int z) {
        return origin.getZ() + x * stepX.getZ() + z * stepZ.getZ();
    }

    public int localX(int worldX, int worldZ) {
        return (worldX - origin.getX()) * stepX.getX() + (worldZ - origin.getZ()) * stepX.getZ();
    }

    public int localY(int worldY) {
        return worldY - origin.getY();
    }

    public int localZ(int worldX, int worldZ) {
        return (worldX - origin.getX()) * stepZ.getX() + (worldZ - origin.getZ()) * stepZ.getZ();
    }

    public BlockState toWorld(BlockState state) {
        return state == null ? null : state.mirror(mirror).rotate(rotation);
    }

    public BlockState toLocal(BlockState state) {
        return state.rotate(back(rotation)).mirror(mirror);
    }

    private static Rotation back(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> rotation;
        };
    }

    public BoundingBox box(Blueprint blueprint) {
        Vec3i min = blueprint.min();
        Vec3i size = blueprint.size();
        return BoundingBox.fromCorners(toWorld(min.getX(), min.getY(), min.getZ()),
            toWorld(min.getX() + size.getX() - 1, min.getY() + size.getY() - 1, min.getZ() + size.getZ() - 1));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BuildFrame frame && origin.equals(frame.origin) && rotation == frame.rotation
            && mirror == frame.mirror;
    }

    @Override
    public int hashCode() {
        return Objects.hash(origin, rotation, mirror);
    }
}
