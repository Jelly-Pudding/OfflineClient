package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CartographyTableBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Shared helpers for modules that place or break blocks.
 */
public final class BlockUtil {

    private static final Minecraft MC = OfflineClient.MC;

    private BlockUtil() {
    }

    public static BlockState state(BlockPos pos) {
        return MC.level.getBlockState(pos);
    }

    /** True for anything a block can be placed into like air or water or grass. */
    public static boolean isReplaceable(BlockPos pos) {
        return state(pos).canBeReplaced();
    }

    /** True for a block that can be stood on or placed against. */
    public static boolean isSolid(BlockPos pos) {
        BlockState state = state(pos);
        return !state.isAir() && state.blocksMotion();
    }

    /** True if the block can be broken by hand or tool at all. */
    public static boolean isBreakable(BlockPos pos) {
        BlockState state = state(pos);
        return !state.isAir() && state.getDestroySpeed(MC.level, pos) >= 0;
    }

    /** Distance from the player's eyes to the middle of the block. */
    public static double distanceTo(BlockPos pos) {
        return MC.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
    }

    /** All block positions inside a box around the player. Nearest first. */
    public static List<BlockPos> positionsWithin(double range) {
        List<BlockPos> result = new ArrayList<>();
        Vec3 eye = MC.player.getEyePosition();
        int r = (int) Math.ceil(range);
        BlockPos center = BlockPos.containing(eye);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (eye.distanceTo(Vec3.atCenterOf(pos)) <= range) {
                        result.add(pos);
                    }
                }
            }
        }
        result.sort((a, b) -> Double.compare(distanceTo(a), distanceTo(b)));
        return result;
    }

    /** True if the player's own hitbox overlaps the block. */
    public static boolean intersectsPlayer(BlockPos pos) {
        AABB block = new AABB(pos);
        return MC.player.getBoundingBox().intersects(block);
    }

    /**
     * Finds a solid neighbor of the target to place against. Returns the
     * direction from the target toward the neighbor or null if there is
     * nothing to build on.
     */
    public static Direction findSupport(BlockPos target) {
        for (Direction side : Direction.values()) {
            if (isSolid(target.relative(side))) {
                return side;
            }
        }
        return null;
    }

    /**
     * Places the held block at the target using the given support side.
     * Rotates toward the click point first when rotate is true. Returns
     * true if the game accepted the placement.
     */
    public static boolean place(BlockPos target, Direction support, boolean rotate, boolean swing) {
        BlockPos against = target.relative(support);
        Direction face = support.getOpposite();
        Vec3 hit = Vec3.atCenterOf(against).add(
            face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);

        if (rotate) {
            faceVector(hit);
        }

        BlockHitResult result = new BlockHitResult(hit, face, against, false);
        InteractionResult outcome = MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, result);
        if (outcome.consumesAction()) {
            if (swing) {
                MC.player.swing(InteractionHand.MAIN_HAND);
            }
            return true;
        }
        return false;
    }

    /**
     * Places the held block by clicking the empty target spot itself. The
     * server accepts a click on a replaceable block as a placement there.
     */
    public static boolean placeDirect(BlockPos target, boolean rotate, boolean swing) {
        if (!isReplaceable(target)) {
            return false;
        }
        Vec3 hit = Vec3.atCenterOf(target);
        if (rotate) {
            faceVector(hit);
        }
        BlockHitResult result = new BlockHitResult(hit, facingSide(target), target, false);
        InteractionResult outcome = MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, result);
        if (outcome.consumesAction()) {
            if (swing) {
                MC.player.swing(InteractionHand.MAIN_HAND);
            }
            return true;
        }
        return false;
    }

    /** Sends a rotation packet toward a point without moving the view. */
    public static void faceVector(Vec3 point) {
        Vec3 eye = MC.player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        MC.player.connection.send(new ServerboundMovePlayerPacket.Rot(
            yaw, Math.clamp(pitch, -90f, 90f), MC.player.onGround(), MC.player.horizontalCollision));
    }

    /** Hotbar slot holding a block item or -1. Prefers the selected slot. */
    public static int findBlockSlot() {
        int selected = MC.player.getInventory().getSelectedSlot();
        if (MC.player.getInventory().getItem(selected).getItem() instanceof BlockItem) {
            return selected;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = MC.player.getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Hotbar slot holding a block item that passes the filter. Minus one when
     * there is none. Prefers the selected slot.
     */
    public static int findBlockSlot(Predicate<Block> filter) {
        int selected = MC.player.getInventory().getSelectedSlot();
        if (slotHolds(selected, filter)) {
            return selected;
        }
        for (int i = 0; i < 9; i++) {
            if (slotHolds(i, filter)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean slotHolds(int slot, Predicate<Block> filter) {
        ItemStack stack = MC.player.getInventory().getItem(slot);
        return stack.getItem() instanceof BlockItem item && filter.test(item.getBlock());
    }

    /**
     * True for a plain full standable cube. Sand and gravel only count
     * when something under the target holds them up.
     */
    public static boolean isBuildingBlock(Block block, BlockPos target) {
        BlockState state = block.defaultBlockState();
        if (!state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            return false;
        }
        if (block instanceof FallingBlock && FallingBlock.isFree(state(target.below()))) {
            return false;
        }
        return !opensOnClick(state);
    }

    /**
     * True for blocks that open a menu or toggle when right clicked.
     * Placing against one opens it instead.
     */
    public static boolean opensOnClick(BlockState state) {
        Block block = state.getBlock();
        return block instanceof BaseEntityBlock
            || block instanceof CraftingTableBlock
            || block instanceof AnvilBlock
            || block instanceof LoomBlock
            || block instanceof CartographyTableBlock
            || block instanceof GrindstoneBlock
            || block instanceof StonecutterBlock
            || block instanceof EnchantingTableBlock
            || block instanceof ButtonBlock
            || block instanceof LeverBlock
            || block instanceof BasePressurePlateBlock
            || block instanceof BedBlock
            || block instanceof FenceGateBlock
            || block instanceof DoorBlock
            || block instanceof TrapDoorBlock
            || block instanceof NoteBlock
            || block instanceof RepeaterBlock
            || block instanceof ComparatorBlock
            || block instanceof CakeBlock
            || block instanceof FlowerPotBlock
            || block instanceof RespawnAnchorBlock;
    }

    /**
     * Like {@link #findSupport} but skips blocks that open on click and
     * prefers the neighbor whose face is closest to the player's eyes.
     * Null if there is nothing usable to build on.
     */
    public static Direction findPlaceSupport(BlockPos target) {
        Vec3 eye = MC.player.getEyePosition();
        Direction best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction side : Direction.values()) {
            BlockPos neighbor = target.relative(side);
            if (!isSolid(neighbor) || opensOnClick(state(neighbor))) {
                continue;
            }
            double distance = eye.distanceToSqr(hitPoint(neighbor, side.getOpposite()));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = side;
            }
        }
        return best;
    }

    /** The point in the middle of one face of a block. Uses the real shape. */
    public static Vec3 hitPoint(BlockPos pos, Direction side) {
        VoxelShape shape = state(pos).getShape(MC.level, pos);
        AABB box = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
        Vec3 center = Vec3.atLowerCornerOf(pos).add(box.getCenter());
        double halfX = (box.maxX - box.minX) * 0.5;
        double halfY = (box.maxY - box.minY) * 0.5;
        double halfZ = (box.maxZ - box.minZ) * 0.5;
        return center.add(side.getStepX() * halfX, side.getStepY() * halfY, side.getStepZ() * halfZ);
    }

    /** The face of a block that sits closest to the player's eyes. */
    public static Direction facingSide(BlockPos pos) {
        Vec3 eye = MC.player.getEyePosition();
        Direction best = Direction.UP;
        double bestDistance = Double.MAX_VALUE;
        for (Direction side : Direction.values()) {
            double distance = eye.distanceToSqr(hitPoint(pos, side));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = side;
            }
        }
        return best;
    }

    /** True if the player is standing on this block. */
    public static boolean isStandingOn(BlockPos pos) {
        VoxelShape shape = state(pos).getCollisionShape(MC.level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        AABB feet = MC.player.getBoundingBox();
        AABB below = new AABB(feet.minX, feet.minY - 0.1, feet.minZ, feet.maxX, feet.minY + 0.05, feet.maxZ);
        return shape.bounds().move(pos).intersects(below);
    }

    /** True if one hit breaks the block right now. */
    public static boolean canInstantBreak(BlockPos pos) {
        if (MC.player.getAbilities().instabuild) {
            return true;
        }
        return state(pos).getDestroyProgress(MC.player, MC.level, pos) >= 1;
    }

    /** Ticks to break the block with the held item. */
    public static int breakTicks(BlockPos pos) {
        float perTick = state(pos).getDestroyProgress(MC.player, MC.level, pos);
        if (perTick <= 0) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(1 / perTick);
    }

    /** True for ore blocks. Nether ores and ancient debris count too. */
    public static boolean isOre(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.ANCIENT_DEBRIS || block == Blocks.GILDED_BLACKSTONE) {
            return true;
        }
        return BuiltInRegistries.BLOCK.getKey(block).getPath().endsWith("_ore");
    }

    /**
     * Groups blocks that are the same thing in different stone. Iron ore
     * and deepslate iron ore share a family.
     */
    public static String family(Block block) {
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return path.startsWith("deepslate_") ? path.substring("deepslate_".length()) : path;
    }

    /** Readable block name for chat and the HUD. */
    public static String blockName(Block block) {
        return block.getName().getString();
    }
}
