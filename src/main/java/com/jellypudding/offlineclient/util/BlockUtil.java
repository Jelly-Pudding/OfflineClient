package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.render.DrawBatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
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
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WitherRoseBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
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
import java.util.SortedSet;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class BlockUtil {

    private static final Minecraft MC = OfflineClient.MC;

    // Blast resistance a block needs to survive a crystal. Obsidian and up.
    public static final float BLAST_PROOF = 600;

    // The widest cube scan that still fits in one tick.
    public static final double MAX_SCAN_RANGE = 16;

    private static final AABB FULL_CUBE = new AABB(0, 0, 0, 1, 1, 1);

    // The server takes a click on a block up to one block past the player's reach.
    private static final double SERVER_REACH_SLACK = 1;

    // The neighbours a block is built up from. Below first and then the four sides.
    public static final List<Direction> BELOW_THEN_SIDES = List.of(
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST);

    // A shulker box opens as far as its own size and its lid lifts half a block. The game trims
    // a sliver off the space it needs.
    private static final float SHULKER_SIZE = 1;
    private static final float SHULKER_LID = 0.5f;
    private static final double SHULKER_SLIVER = 1.0E-6;

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

    // Solid with the two exceptions a player can walk into.
    @SuppressWarnings("deprecation")
    public static boolean blocksMotion(BlockState state) {
        return state.isSolid()
            && !state.is(Blocks.COBWEB) && !state.is(Blocks.BAMBOO_SAPLING);
    }

    // Standing in or on one of these hurts or traps the player.
    public static boolean harmful(BlockState state) {
        Block block = state.getBlock();
        return block instanceof BaseFireBlock
            || block instanceof MagmaBlock
            || block instanceof CactusBlock
            || block instanceof SweetBerryBushBlock
            || block instanceof PowderSnowBlock
            || block instanceof CampfireBlock
            || block instanceof WitherRoseBlock;
    }

    public static boolean isBreakable(BlockPos pos) {
        BlockState state = state(pos);
        return !state.isAir() && state.getDestroySpeed(MC.level, pos) >= 0;
    }

    // The deepest crack anyone has made in the block from nought to nine. Your own digging
    // counts. Minus one whilst nobody is breaking it.
    public static int crackStage(BlockPos pos) {
        ClientLevel level = MC.level;
        SortedSet<BlockDestructionProgress> cracks = level == null ? null
            : level.destructionProgress().get(pos.asLong());
        return cracks == null || cracks.isEmpty() ? -1 : cracks.last().getProgress();
    }

    // True whilst someone other than you or a friend is breaking the block. Every crack the
    // server sends carries the id of whoever makes it.
    public static boolean minedByOthers(BlockPos pos) {
        SortedSet<BlockDestructionProgress> cracks = MC.level.destructionProgress().get(pos.asLong());
        if (cracks == null) {
            return false;
        }
        for (BlockDestructionProgress crack : cracks) {
            if (crack.getId() != MC.player.getId() && !EntityUtil.isFriend(MC.level.getEntity(crack.getId()))) {
                return true;
            }
        }
        return false;
    }

    // The three coordinates with spaces between.
    public static String text(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    // Reads back a position that text wrote. Null when the text is not three whole numbers.
    public static BlockPos parse(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Scored(BlockPos pos, double distanceSq) {
    }

    // Nearest first.
    public static List<BlockPos> positionsWithin(double range) {
        // Sorting on a fresh Vec3 per comparison allocates millions at this radius.
        List<Scored> scored = new ArrayList<>();
        scan(range, (pos, distanceSq) -> scored.add(new Scored(pos.immutable(), distanceSq)));
        scored.sort(Comparator.comparingDouble(Scored::distanceSq));
        List<BlockPos> result = new ArrayList<>(scored.size());
        for (Scored entry : scored) {
            result.add(entry.pos());
        }
        return result;
    }

    @FunctionalInterface
    private interface ScanVisitor {
        void visit(BlockPos.MutableBlockPos pos, double distanceSq);
    }

    // Every block whose middle lies within the range of the eyes. The same mutable
    // position is handed over each time and is only valid inside the call.
    private static void scan(double range, ScanVisitor visitor) {
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
                    double distanceSq = ox * ox + oy * oy + oz * oz;
                    if (distanceSq <= limitSq) {
                        visitor.visit(cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz),
                            distanceSq);
                    }
                }
            }
        }
    }

    // Every position in range in no particular order. The position is only valid inside the call.
    public static void forEachWithin(double range, Consumer<BlockPos> action) {
        scan(range, (pos, distanceSq) -> action.accept(pos));
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

    // The block under the crosshair or null when it rests on anything else.
    public static BlockHitResult aimedBlock() {
        return MC.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    // Where a look straight ahead from the eyes ends within the range. A miss names the empty
    // block at the far end. The camera plays no part and Freecam cannot move it. The look
    // starts from the eyes and view of this moment as the crosshair does.
    public static BlockHitResult look(double range) {
        return (BlockHitResult) MC.player.pick(range, 1, false);
    }

    // The empty block the eyes look into at the end of the range. Null when the look meets a
    // block first or the spot is outside the world or cannot take a block.
    public static BlockPos airSpot(double range) {
        BlockHitResult hit = look(range);
        if (hit.getType() != HitResult.Type.MISS) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        return Level.isInSpawnableBounds(pos) && isReplaceable(pos) ? pos : null;
    }

    public static double distanceTo(BlockPos pos) {
        return MC.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
    }

    // The reach the server measures with. Reach and AirPlace grow the client's own figure.
    public static double serverBlockReach() {
        return MC.player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
    }

    // The furthest from the eyes the server takes a click on a block.
    public static double serverReachLimit() {
        return serverBlockReach() + SERVER_REACH_SLACK;
    }

    // The point of the block nearest the eyes. The server measures reach to it.
    public static Vec3 nearestPoint(BlockPos pos, Vec3 eye) {
        return new Vec3(Math.clamp(eye.x, pos.getX(), pos.getX() + 1),
            Math.clamp(eye.y, pos.getY(), pos.getY() + 1),
            Math.clamp(eye.z, pos.getZ(), pos.getZ() + 1));
    }

    public static double reachTo(BlockPos pos) {
        return reachTo(pos, MC.player.getEyePosition());
    }

    // From eyes somewhere else such as a spot the player could stand on.
    public static double reachTo(BlockPos pos, Vec3 eye) {
        return eye.distanceTo(nearestPoint(pos, eye));
    }

    // True when the server takes a click on this block.
    public static boolean serverReaches(BlockPos pos) {
        return serverReaches(reachTo(pos));
    }

    private static boolean serverReaches(double distance) {
        return distance < serverReachLimit();
    }

    // Within the range a module allows and the server takes.
    public static boolean reaches(BlockPos pos, double range) {
        return reaches(pos, MC.player.getEyePosition(), range);
    }

    public static boolean reaches(BlockPos pos, Vec3 eye, double range) {
        double distance = reachTo(pos, eye);
        return distance <= range && serverReaches(distance);
    }

    // How far away a spot may be. One out of sight gets the walls range.
    public static double reachFor(Vec3 aim, double range, double wallsRange) {
        return canSee(aim) ? range : wallsRange;
    }

    // Near enough to the eyes for a click on the block.
    public static boolean inReach(BlockPos pos) {
        return distanceTo(pos) <= serverBlockReach();
    }

    public static boolean inReach(BlockPos pos, double range, double wallsRange) {
        return distanceTo(pos) <= reachFor(Vec3.atCenterOf(pos), range, wallsRange);
    }

    // The space a block placed by this click fills. A replaceable block is filled itself.
    public static BlockPos placeSpot(BlockHitResult hit) {
        BlockPos clicked = hit.getBlockPos();
        return isReplaceable(clicked) ? clicked : clicked.relative(hit.getDirection());
    }

    // True whilst any part of the box is inside a cobweb. As in vanilla a web that
    // only touches the outside of the box does not count.
    public static boolean inCobweb(Entity entity) {
        return entity.level().getBlockStates(entity.getBoundingBox().deflate(1.0E-5))
            .anyMatch(state -> state.is(Blocks.COBWEB));
    }

    // True when any block the box reaches into holds lava.
    public static boolean touchesLava(Level level, AABB box) {
        return level.getBlockStates(box).anyMatch(state -> state.getFluidState().is(FluidTags.LAVA));
    }

    // Powder snow only holds up someone in leather boots. Anyone else sinks into it.
    public static boolean touchesPowderSnow(Level level, AABB box) {
        return level.getBlockStates(box).anyMatch(state -> state.is(Blocks.POWDER_SNOW));
    }

    // Places against the neighbour in the support direction. True when the click was taken.
    public static boolean place(BlockPos target, Direction support, boolean rotate, boolean swing) {
        return place(target, support, rotate, swing, InteractionHand.MAIN_HAND);
    }

    public static boolean place(BlockPos target, Direction support, boolean rotate, boolean swing,
                                InteractionHand hand) {
        BlockPos against = target.relative(support);
        Direction face = support.getOpposite();
        Vec3 hit = Vec3.atCenterOf(against).add(
            face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);

        if (rotate) {
            faceVector(hit);
        }

        return click(new BlockHitResult(hit, face, against, false), swing, hand);
    }

    // Right clicks a face. True when the game accepted the click.
    private static boolean click(BlockHitResult hit, boolean swing, InteractionHand hand) {
        InteractionResult outcome = MC.gameMode.useItemOn(MC.player, hand, hit);
        if (!outcome.consumesAction()) {
            return false;
        }
        if (swing) {
            SwingMode.swingUse(hand);
        }
        return true;
    }

    // A click that places at pos. A null support clicks the empty spot itself.
    public record Placement(BlockPos pos, Direction support, Vec3 hit) {

        // Turns the way the face mode says then clicks and swings. False when nothing went down.
        public boolean place(FaceMode face, SwingMode swing) {
            face.face(hit, RotationPriority.PLACE);
            boolean placed = support == null
                ? placeDirect(pos, hit, false, false)
                : BlockUtil.place(pos, support, false, false);
            if (!placed) {
                return false;
            }
            swing.use();
            MC.rightClickDelay = InputUtil.USE_DELAY;
            return true;
        }
    }

    // A click the server takes that puts a block at the spot. Against a neighbour face when one
    // is in reach and straight into the empty spot otherwise. Null when neither would land.
    // The click into the spot lands on its point nearest the eyes. The server takes any point
    // inside the block.
    public static Placement reachablePlacement(BlockPos pos, double range, boolean needSight) {
        Placement leaning = placementInReach(pos, range, needSight);
        if (leaning != null && serverReaches(pos.relative(leaning.support()))) {
            return leaning;
        }
        if (!reaches(pos, range)) {
            return null;
        }
        Vec3 point = nearestPoint(pos, MC.player.getEyePosition());
        return needSight && !canSee(point) ? null : new Placement(pos, null, point);
    }

    // Null unless a neighbour face sits within range of the eyes and in sight when that is asked for.
    public static Placement placementInReach(BlockPos pos, double range, boolean needSight) {
        Direction support = findPlaceSupport(pos);
        if (support == null) {
            return null;
        }
        Vec3 hit = hitPoint(pos.relative(support), support.getOpposite());
        if (MC.player.getEyePosition().distanceToSqr(hit) > range * range) {
            return null;
        }
        return needSight && !canSee(hit) ? null : new Placement(pos, support, hit);
    }

    // Places against a neighbour when there is one and clicks the spot itself otherwise.
    public static boolean placeAny(BlockPos target, boolean rotate, boolean swing) {
        Direction support = findPlaceSupport(target);
        return support != null ? place(target, support, rotate, swing) : placeDirect(target, rotate, swing);
    }

    // Holds the hotbar slot for one placement and swaps back straight after. A slot of
    // minus one places nothing.
    public static boolean placeFrom(InventoryUtil.SlotSwap slots, int slot, BlockPos target, boolean rotate) {
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        boolean placed = placeAny(target, rotate, true);
        slots.restore();
        return placed;
    }

    public static boolean isWebbable(BlockPos pos) {
        return isReplaceable(pos) && state(pos).getBlock() != Blocks.COBWEB;
    }

    public static boolean placeWeb(InventoryUtil.SlotSwap slots, BlockPos pos, boolean rotate) {
        return isWebbable(pos)
            && placeFrom(slots, InventoryUtil.hotbarSlot(stack -> stack.is(Items.COBWEB)), pos, rotate);
    }

    // The server accepts a click on a replaceable block as a placement there.
    public static boolean placeDirect(BlockPos target, boolean rotate, boolean swing) {
        return placeDirect(target, Vec3.atCenterOf(target), rotate, swing);
    }

    // The hit point decides which half a slab or a stair lands in.
    public static boolean placeDirect(BlockPos target, Vec3 hit, boolean rotate, boolean swing) {
        return placeDirect(target, hit, rotate, swing, InteractionHand.MAIN_HAND);
    }

    public static boolean placeDirect(BlockPos target, Vec3 hit, boolean rotate, boolean swing,
                                      InteractionHand hand) {
        if (!isReplaceable(target)) {
            return false;
        }
        if (rotate) {
            faceVector(hit);
        }
        return click(new BlockHitResult(hit, facingSide(target), target, false), swing, hand);
    }

    // Looks at a point without moving the view.
    public static void faceVector(Vec3 point) {
        faceVector(point, RotationPriority.PLACE);
    }

    public static void faceVector(Vec3 point, RotationPriority priority) {
        RotationManager.request(RotationManager.yawTo(point), RotationManager.pitchTo(point), priority);
    }

    public static void centerPlayer() {
        centerPlayer(MC.player.blockPosition());
    }

    // Moves the player level to the middle of the column the block stands in.
    public static void centerPlayer(BlockPos block) {
        double x = block.getX() + 0.5;
        double z = block.getZ() + 0.5;
        if (Math.abs(MC.player.getX() - x) < 0.01 && Math.abs(MC.player.getZ() - z) < 0.01) {
            return;
        }
        MC.player.setPos(x, MC.player.getY(), z);
        MoveGate.send(x, MC.player.getY(), z, MC.player.onGround());
    }

    // Lower wins. Minus one means the block is not on the list.
    private static int rankOf(Block block, Collection<Identifier> preferred) {
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
        BlockPos[] best = {null};
        double[] bestDistanceSq = {Double.MAX_VALUE};
        scan(range, (pos, distanceSq) -> {
            // Anything further out than the best hit cannot win and is never tested.
            if (distanceSq > bestDistanceSq[0]) {
                return;
            }
            BlockPos candidate = pos.immutable();
            if (test.test(candidate)) {
                bestDistanceSq[0] = distanceSq;
                best[0] = candidate;
            }
        });
        return best[0];
    }

    // The position whose middle lies nearest the eyes of those that pass the test. Null when none
    // does. A mutable position from a box walk is copied before it is kept.
    public static BlockPos nearestOf(Iterable<BlockPos> positions, Predicate<BlockPos> test) {
        Vec3 eye = MC.player.getEyePosition();
        BlockPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (BlockPos pos : positions) {
            double distanceSq = eye.distanceToSqr(Vec3.atCenterOf(pos));
            // A position no nearer than the best hit cannot win and is never tested.
            if (distanceSq >= bestDistanceSq || !test.test(pos)) {
                continue;
            }
            bestDistanceSq = distanceSq;
            best = pos.immutable();
        }
        return best;
    }

    public static boolean isBlastProof(BlockPos pos) {
        return isBlastProof(state(pos));
    }

    // A crystal or a bed cannot break it.
    public static boolean isBlastProof(BlockState state) {
        return state.getBlock().getExplosionResistance() >= BLAST_PROOF;
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

    // The hotbar slot holding whichever allowed block stands up best to a blast. Blast
    // resistance decides and the time it takes to break settles a tie. Minus one when none is there.
    public static int findStrongestBlockSlot(Predicate<Block> allowed) {
        int best = -1;
        float bestResistance = -1;
        float bestHardness = -1;
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = MC.player.getInventory().getItem(i);
            if (!(stack.getItem() instanceof BlockItem item) || !allowed.test(item.getBlock())) {
                continue;
            }
            Block block = item.getBlock();
            float resistance = block.getExplosionResistance();
            // A block that never breaks outlasts every other.
            float hardness = block.defaultDestroyTime() < 0 ? Float.MAX_VALUE : block.defaultDestroyTime();
            if (resistance > bestResistance || (resistance == bestResistance && hardness > bestHardness)) {
                best = i;
                bestResistance = resistance;
                bestHardness = hardness;
            }
        }
        return best;
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

    // No entity stands where the block would go. The server refuses a placement into one.
    public static boolean unobstructed(BlockPos pos, BlockState state) {
        return MC.level.isUnobstructed(state, pos, CollisionContext.empty());
    }

    public static boolean unobstructed(BlockPos pos) {
        return unobstructed(pos, Blocks.OBSIDIAN.defaultBlockState());
    }

    // A full block would go in. The spot is replaceable and nobody stands in it.
    public static boolean blockFits(BlockPos pos) {
        return isReplaceable(pos) && unobstructed(pos);
    }

    // A standing torch would go in here. It has no collision and nobody standing in the spot stops it.
    // A floor that opens on a click would open instead of taking the torch.
    public static boolean torchStands(BlockPos pos) {
        BlockPos floor = pos.below();
        return isReplaceable(pos) && Block.canSupportCenter(MC.level, floor, Direction.UP)
            && !opensOnClick(state(floor));
    }

    // A plain or soul or copper torch standing or on a wall. Redstone torches are not lights.
    public static boolean isTorch(Block block) {
        return block instanceof TorchBlock;
    }

    public static int findTorchSlot() {
        return findBlockSlot(BlockUtil::isTorch);
    }

    // Turns the way the face mode says towards the nearest face and clicks it.
    public static boolean interact(BlockPos pos, FaceMode face) {
        Direction side = facingSide(pos);
        face.face(hitPoint(pos, side), RotationPriority.PLACE);
        return interact(pos, side);
    }

    // Clicks a block face. Vanilla skips a block interaction whilst the player sneaks.
    // The sneak is dropped for the click and put back straight after.
    public static boolean interact(BlockPos pos, Direction side) {
        return interact(pos, side, InteractionHand.MAIN_HAND);
    }

    public static boolean interact(BlockPos pos, Direction side, InteractionHand hand) {
        BlockHitResult result = new BlockHitResult(hitPoint(pos, side), side, pos, false);
        boolean used = InputUtil.whileStanding(() -> MC.gameMode
            .useItemOn(MC.player, hand, result).consumesAction());
        if (used) {
            SwingMode.swingUse(hand);
        }
        return used;
    }

    // A plain building block that also stays where it is put. A chorus flower is a full cube
    // and still breaks away from end stone.
    public static boolean buildsAt(Block block, BlockPos target) {
        return isBuildingBlock(block, target) && block.defaultBlockState().canSurvive(MC.level, target);
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

    // The outline the game gives the block pulled in off its faces as a drawn block is. A block
    // without an outline gets a whole block.
    public static AABB outlineBox(BlockPos pos, BlockState state) {
        VoxelShape shape = state.getShape(MC.level, pos);
        return shape.isEmpty() ? DrawBatch.blockBox(pos) : shape.bounds().move(pos).deflate(DrawBatch.BLOCK_INSET);
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
        return click(new BlockHitResult(hit, side, pos, false), swing, InteractionHand.MAIN_HAND);
    }

    public static boolean isWaterSource(BlockPos pos) {
        return MC.level.getFluidState(pos).isSourceOfType(Fluids.WATER);
    }

    // Water poured here boils away as it does in the Nether.
    public static boolean waterEvaporates(BlockPos pos) {
        return MC.level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos);
    }

    // True when liquid beside or above the block would pour in once it came out. Liquid below
    // never flows up.
    public static boolean holdsBackLiquid(BlockPos pos) {
        return holdsBackLiquid(pos, liquid -> true);
    }

    // The same for the liquids the test picks.
    public static boolean holdsBackLiquid(BlockPos pos, Predicate<FluidState> picked) {
        for (Direction side : Direction.values()) {
            FluidState liquid = state(pos.relative(side)).getFluidState();
            if (side != Direction.DOWN && !liquid.isEmpty() && picked.test(liquid)) {
                return true;
            }
        }
        return false;
    }

    // The other half of a double chest. Null for a single chest or any other block.
    public static BlockPos otherChestHalf(BlockPos pos, BlockState state) {
        if (!state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        return pos.relative(ChestBlock.getConnectedDirection(state));
    }

    // True for a container the game will not open where it stands. A chest stays shut with a
    // solid block or a sitting cat over either half and an ender chest with a solid block over it.
    // A closed shulker box has to lift its lid into space no block or boat fills.
    public static boolean keptShut(BlockEntity container) {
        BlockPos pos = container.getBlockPos();
        if (container instanceof ChestBlockEntity) {
            BlockPos other = otherChestHalf(pos, container.getBlockState());
            return ChestBlock.isChestBlockedAt(MC.level, pos)
                || other != null && ChestBlock.isChestBlockedAt(MC.level, other);
        }
        if (container instanceof EnderChestBlockEntity) {
            BlockPos above = pos.above();
            return MC.level.getBlockState(above).isRedstoneConductor(MC.level, above);
        }
        if (container instanceof ShulkerBoxBlockEntity box
            && box.getAnimationStatus() == ShulkerBoxBlockEntity.AnimationStatus.CLOSED) {
            Direction facing = container.getBlockState().getValue(ShulkerBoxBlock.FACING);
            AABB lid = Shulker.getProgressDeltaAabb(SHULKER_SIZE, facing, 0, SHULKER_LID, Vec3.atBottomCenterOf(pos))
                .deflate(SHULKER_SLIVER);
            return !MC.level.noCollision(lid);
        }
        return false;
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

    // Every block the ores tag names with ancient debris and gilded blackstone as well.
    public static boolean isOre(BlockState state) {
        return state.is(BlockTags.ORES) || state.is(Blocks.ANCIENT_DEBRIS) || state.is(Blocks.GILDED_BLACKSTONE);
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
