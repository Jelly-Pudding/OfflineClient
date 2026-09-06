package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
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
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class BlockUtil {

    private static final Minecraft MC = OfflineClient.MC;

    // Blast resistance a block needs to survive a crystal. Obsidian and up.
    public static final float BLAST_PROOF = 600;

    // The widest cube scan that still fits in one tick.
    private static final double MAX_SCAN_RANGE = 16;

    private static final AABB FULL_CUBE = new AABB(0, 0, 0, 1, 1, 1);

    // Top covers the head. Full seals the sides at head height as well.
    public enum TrapMode { TOP, FULL }

    private BlockUtil() {
    }

    public static BlockState state(BlockPos pos) {
        return MC.level.getBlockState(pos);
    }

    // Outside the world the game hands back void air which reads as replaceable.
    // The server refuses every placement there.
    public static boolean isReplaceable(BlockPos pos) {
        return MC.level.isInWorldBounds(pos) && state(pos).canBeReplaced();
    }

    public static boolean isSolid(BlockPos pos) {
        BlockState state = state(pos);
        return !state.isAir() && blocksMotion(state);
    }

    // Deprecated in vanilla with no replacement. Still the only check that treats
    // cobwebs and bamboo saplings as passable.
    @SuppressWarnings("deprecation")
    public static boolean blocksMotion(BlockState state) {
        return state.blocksMotion();
    }

    public static boolean isBreakable(BlockPos pos) {
        BlockState state = state(pos);
        return !state.isAir() && state.getDestroySpeed(MC.level, pos) >= 0;
    }

    public static double distanceTo(BlockPos pos) {
        return MC.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
    }

    private record Scored(BlockPos pos, double distanceSq) {
    }

    // Nearest first.
    public static List<BlockPos> positionsWithin(double range) {
        range = Math.min(range, MAX_SCAN_RANGE);
        Vec3 eye = MC.player.getEyePosition();
        double limitSq = range * range;
        int r = (int) Math.ceil(range);
        BlockPos center = BlockPos.containing(eye);
        // Sorting on a fresh Vec3 per comparison allocates millions at this radius.
        List<Scored> scored = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    double ox = pos.getX() + 0.5 - eye.x;
                    double oy = pos.getY() + 0.5 - eye.y;
                    double oz = pos.getZ() + 0.5 - eye.z;
                    double distanceSq = ox * ox + oy * oy + oz * oz;
                    if (distanceSq <= limitSq) {
                        scored.add(new Scored(pos, distanceSq));
                    }
                }
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::distanceSq));
        List<BlockPos> result = new ArrayList<>(scored.size());
        for (Scored entry : scored) {
            result.add(entry.pos());
        }
        return result;
    }

    // Every position in range in no particular order. The same mutable
    // position is handed over each time and is only valid inside the call.
    public static void forEachWithin(double range, Consumer<BlockPos> action) {
        range = Math.min(range, MAX_SCAN_RANGE);
        Vec3 eye = MC.player.getEyePosition();
        BlockPos centre = BlockPos.containing(eye);
        int r = (int) Math.ceil(range);
        double limitSq = range * range;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    double ox = centre.getX() + dx + 0.5 - eye.x;
                    double oy = centre.getY() + dy + 0.5 - eye.y;
                    double oz = centre.getZ() + dz + 0.5 - eye.z;
                    if (ox * ox + oy * oy + oz * oz <= limitSq) {
                        action.accept(cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz));
                    }
                }
            }
        }
    }

    public static Iterable<BlockPos> positionsAround(BlockPos center, int radius) {
        return BlockPos.betweenClosed(
            center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius));
    }

    public static boolean diggable(BlockPos pos) {
        BlockState state = state(pos);
        if (state.isAir() || !isBreakable(pos)) {
            return false;
        }
        return !state.getShape(MC.level, pos).isEmpty();
    }

    public static boolean intersectsPlayer(BlockPos pos) {
        AABB block = new AABB(pos);
        return MC.player.getBoundingBox().intersects(block);
    }

    // Places against the neighbour in the support direction. True when the click was taken.
    public static boolean place(BlockPos target, Direction support, boolean rotate, boolean swing) {
        BlockPos against = target.relative(support);
        Direction face = support.getOpposite();
        Vec3 hit = Vec3.atCenterOf(against).add(
            face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);

        if (rotate) {
            faceVector(hit);
        }

        return click(new BlockHitResult(hit, face, against, false), swing);
    }

    // Right clicks a face. True when the game accepted the click.
    private static boolean click(BlockHitResult hit, boolean swing) {
        InteractionResult outcome = MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, hit);
        if (!outcome.consumesAction()) {
            return false;
        }
        if (swing) {
            MC.player.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    // Places against a neighbour when there is one and clicks the spot itself otherwise.
    public static boolean placeAny(BlockPos target, boolean rotate, boolean swing) {
        Direction support = findPlaceSupport(target);
        return support != null ? place(target, support, rotate, swing) : placeDirect(target, rotate, swing);
    }

    // The server accepts a click on a replaceable block as a placement there.
    public static boolean placeDirect(BlockPos target, boolean rotate, boolean swing) {
        return placeDirect(target, Vec3.atCenterOf(target), rotate, swing);
    }

    // The hit point decides which half a slab or a stair lands in.
    public static boolean placeDirect(BlockPos target, Vec3 hit, boolean rotate, boolean swing) {
        if (!isReplaceable(target)) {
            return false;
        }
        if (rotate) {
            faceVector(hit);
        }
        return click(new BlockHitResult(hit, facingSide(target), target, false), swing);
    }

    // Looks at a point without moving the view.
    public static void faceVector(Vec3 point) {
        faceVector(point, RotationPriority.PLACE);
    }

    public static void faceVector(Vec3 point, RotationPriority priority) {
        RotationManager.request(RotationManager.yawTo(point), RotationManager.pitchTo(point), priority);
    }

    public static void centerPlayer() {
        double x = Mth.floor(MC.player.getX()) + 0.5;
        double z = Mth.floor(MC.player.getZ()) + 0.5;
        if (Math.abs(MC.player.getX() - x) < 0.01 && Math.abs(MC.player.getZ() - z) < 0.01) {
            return;
        }
        MC.player.setPos(x, MC.player.getY(), z);
        MC.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            x, MC.player.getY(), z, MC.player.onGround(), MC.player.horizontalCollision));
    }

    // Lower wins. Minus one means the block is not on the list.
    public static int rankOf(Block block, Collection<Identifier> preferred) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        int rank = 0;
        for (Identifier chosen : preferred) {
            if (chosen.equals(id)) {
                return rank;
            }
            rank++;
        }
        return -1;
    }

    // Hotbar slot holding a block item or minus one. Prefers the selected slot.
    public static int findBlockSlot() {
        return InventoryUtil.hotbarSlot(stack -> stack.getItem() instanceof BlockItem);
    }

    // The closest position within the range that passes the test.
    public static BlockPos nearestWithin(double range, Predicate<BlockPos> test) {
        range = Math.min(range, MAX_SCAN_RANGE);
        Vec3 eye = MC.player.getEyePosition();
        BlockPos centre = BlockPos.containing(eye);
        int r = (int) Math.ceil(range);
        double bestDistanceSq = range * range;
        BlockPos best = null;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    double ox = centre.getX() + dx + 0.5 - eye.x;
                    double oy = centre.getY() + dy + 0.5 - eye.y;
                    double oz = centre.getZ() + dz + 0.5 - eye.z;
                    double distanceSq = ox * ox + oy * oy + oz * oz;
                    // Anything further out than the best hit cannot win.
                    if (distanceSq > bestDistanceSq) {
                        continue;
                    }
                    BlockPos pos = centre.offset(dx, dy, dz);
                    if (test.test(pos)) {
                        bestDistanceSq = distanceSq;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    public static boolean isBlastProof(BlockPos pos) {
        return state(pos).getBlock().getExplosionResistance() >= BLAST_PROOF;
    }

    // True whilst the player stands in a hole walled with blast proof blocks.
    // A two block hole counts when its second block is walled on its own.
    public static boolean playerInHole() {
        BlockPos feet = MC.player.blockPosition();
        if (!isBlastProof(feet.below())) {
            return false;
        }
        int open = 0;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos next = feet.relative(side);
            if (isBlastProof(next)) {
                continue;
            }
            open++;
            if (!isBlastProof(next.below())) {
                return false;
            }
            for (Direction other : Direction.Plane.HORIZONTAL) {
                if (other != side.getOpposite() && !isBlastProof(next.relative(other))) {
                    return false;
                }
            }
        }
        return open < 2;
    }

    // A hotbar slot holding something that survives a crystal and can be broken again.
    // Bedrock and other unbreakable blocks are no use.
    public static int findBlastProofSlot() {
        return findBlockSlot(block ->
            block.getExplosionResistance() >= BLAST_PROOF && block.defaultDestroyTime() >= 0);
    }

    public static int findBlockSlot(Predicate<Block> filter) {
        return InventoryUtil.hotbarSlot(stack ->
            stack.getItem() instanceof BlockItem item && filter.test(item.getBlock()));
    }

    // The hotbar slot holding whichever allowed block sits highest up the list.
    public static int findRankedBlockSlot(Collection<Identifier> preferred, Predicate<Block> allowed) {
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = MC.player.getInventory().getItem(i);
            if (!(stack.getItem() instanceof BlockItem item) || !allowed.test(item.getBlock())) {
                continue;
            }
            int rank = rankOf(item.getBlock(), preferred);
            if (rank != -1 && rank < bestRank) {
                bestRank = rank;
                best = i;
            }
        }
        return best;
    }

    // The open spots above the head and at head height around it.
    public static List<BlockPos> trapSpots(BlockPos feet, boolean sealSides) {
        return trapSpots(feet, true, sealSides, false);
    }

    // Any mix of the block over the head and the four at head height and the one underfoot.
    public static List<BlockPos> trapSpots(BlockPos feet, boolean top, boolean sides, boolean bottom) {
        List<BlockPos> spots = new ArrayList<>();
        if (top) {
            addOpen(spots, feet.above(2));
        }
        if (sides) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                addOpen(spots, feet.above().relative(side));
            }
        }
        if (bottom) {
            addOpen(spots, feet.below());
        }
        return spots;
    }

    // A spot only counts when a full block would actually fit in it.
    private static void addOpen(List<BlockPos> spots, BlockPos pos) {
        if (isReplaceable(pos) && MC.level.isUnobstructed(
            Blocks.OBSIDIAN.defaultBlockState(), pos, CollisionContext.empty())) {
            spots.add(pos);
        }
    }

    // Clicks a block face. Vanilla skips a block interaction whilst the player sneaks.
    // The sneak is dropped for the click and put back straight after.
    public static boolean interact(BlockPos pos, Direction side) {
        boolean sneaking = MC.player.isShiftKeyDown();
        if (sneaking) {
            MC.player.setShiftKeyDown(false);
        }
        BlockHitResult result = new BlockHitResult(hitPoint(pos, side), side, pos, false);
        boolean used = MC.gameMode
            .useItemOn(MC.player, InteractionHand.MAIN_HAND, result).consumesAction();
        if (sneaking) {
            MC.player.setShiftKeyDown(true);
        }
        if (used) {
            MC.player.swing(InteractionHand.MAIN_HAND);
        }
        return used;
    }

    // True for a plain full standable cube.
    // Sand and gravel need something under the target to hold them up.
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

    // Placing against one of these opens it instead.
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

    // A solid neighbour to place against. Blocks that open on click are skipped.
    // The face closest to the eyes wins.
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

    // Taken from the real shape and not from a full cube.
    public static Vec3 hitPoint(BlockPos pos, Direction side) {
        VoxelShape shape = state(pos).getShape(MC.level, pos);
        AABB box = shape.isEmpty() ? FULL_CUBE : shape.bounds();
        Vec3 center = Vec3.atLowerCornerOf(pos).add(box.getCenter());
        double halfX = (box.maxX - box.minX) * 0.5;
        double halfY = (box.maxY - box.minY) * 0.5;
        double halfZ = (box.maxZ - box.minZ) * 0.5;
        return center.add(side.getStepX() * halfX, side.getStepY() * halfY, side.getStepZ() * halfZ);
    }

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

    // True when nothing solid sits between the eyes and the point.
    public static boolean canSee(Vec3 point) {
        BlockHitResult hit = MC.level.clip(new ClipContext(MC.player.getEyePosition(), point,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, MC.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static boolean canSee(BlockPos pos) {
        Vec3 eye = MC.player.getEyePosition();
        Vec3 point = hitPoint(pos, facingSide(pos));
        BlockHitResult hit = MC.level.clip(new ClipContext(eye, point,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, MC.player));
        // A target with no collider lets the ray run all the way to the end point.
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    // A plain right click on the nearest face. Nothing is placed by the call itself.
    public static boolean useOn(BlockPos pos, boolean rotate, boolean swing) {
        Direction side = facingSide(pos);
        Vec3 hit = hitPoint(pos, side);
        if (rotate) {
            faceVector(hit);
        }
        return click(new BlockHitResult(hit, side, pos, false), swing);
    }

    public static boolean isStandingOn(BlockPos pos) {
        VoxelShape shape = state(pos).getCollisionShape(MC.level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        AABB feet = MC.player.getBoundingBox();
        AABB below = new AABB(feet.minX, feet.minY - 0.1, feet.minZ, feet.maxX, feet.minY + 0.05, feet.maxZ);
        return shape.bounds().move(pos).intersects(below);
    }

    public static boolean canInstantBreak(BlockPos pos) {
        if (MC.player.getAbilities().instabuild) {
            return true;
        }
        return state(pos).getDestroyProgress(MC.player, MC.level, pos) >= 1;
    }

    // Progress one tick of mining with this tool would make. The tool need not be held.
    // Follows the vanilla dig speed maths so a packet miner can time a tool it swapped away.
    public static float breakDelta(ItemStack tool, BlockPos pos) {
        BlockState state = state(pos);
        float hardness = state.getDestroySpeed(MC.level, pos);
        if (hardness < 0) {
            return 0;
        }
        if (hardness == 0) {
            return 1;
        }
        Player player = MC.player;
        float speed = ItemUtil.miningSpeed(tool, state);
        if (MobEffectUtil.hasDigSpeed(player)) {
            speed *= 1 + (MobEffectUtil.getDigSpeedAmplification(player) + 1) * 0.2f;
        }
        MobEffectInstance fatigue = player.getEffect(MobEffects.MINING_FATIGUE);
        if (fatigue != null) {
            speed *= switch (fatigue.getAmplifier()) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 8.1E-4f;
            };
        }
        speed *= (float) player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);
        if (player.isEyeInFluid(FluidTags.WATER)) {
            speed *= (float) player.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
        }
        if (!player.onGround()) {
            speed /= 5;
        }
        boolean rightTool = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        return speed / hardness / (rightTool ? 30 : 100);
    }

    public static int breakTicks(BlockPos pos) {
        float perTick = state(pos).getDestroyProgress(MC.player, MC.level, pos);
        if (perTick <= 0) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(1 / perTick);
    }

    // Ancient debris and gilded blackstone count too.
    public static boolean isOre(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.ANCIENT_DEBRIS || block == Blocks.GILDED_BLACKSTONE) {
            return true;
        }
        return BuiltInRegistries.BLOCK.getKey(block).getPath().endsWith("_ore");
    }

    // Iron ore and deepslate iron ore share a family.
    public static String family(Block block) {
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return path.startsWith("deepslate_") ? path.substring("deepslate_".length()) : path;
    }

    public static String blockName(Block block) {
        return block.getName().getString();
    }
}
