package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

// Puts something soft under a falling player just before the ground arrives and
// takes the water or powder snow back once they have landed.
public final class SoftLanding {

    public enum Kind {
        WATER(Items.WATER_BUCKET),
        POWDER_SNOW(Items.POWDER_SNOW_BUCKET),
        COBWEB(Items.COBWEB),
        SLIME_BLOCK(Items.SLIME_BLOCK),
        HAY_BALE(Items.HAY_BLOCK),
        HONEY_BLOCK(Items.HONEY_BLOCK);

        private final Item item;

        Kind(Item item) {
            this.item = item;
        }

        public ItemStack icon() {
            return new ItemStack(item);
        }
    }

    // A landing that fits the ground below. Lowest is the lowest the feet may be for it
    // to still go down. Sneaking pours water past a block that would swallow it and stops
    // a click from opening a chest or a door.
    private record Plan(Kind kind, BlockPos ground, BlockPos spot, boolean sneak, double lowest) {
    }

    private static final Minecraft MC = OfflineClient.MC;

    // A placed block needs the feet this far above the bottom of its space.
    private static final double ROOM = 1;

    // Keeps the ground a hair inside the reach of the ray that finds it.
    private static final double REACH_MARGIN = 0.05;

    // The top of a still water source as a share of its block.
    private static final double WATER_SURFACE = 8 / 9.0;

    // A ray that stops within this of a water surface stopped on the water.
    private static final double SURFACE_SLACK = 1.0E-3;

    // Ticks a landing waits to be collected before it is left where it is.
    private static final int GIVE_UP = 60;

    // Water poured into a block only shows once the server's update arrives.
    private static final int ECHO = 20;

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();

    // What went down and where. Null once it is collected or given up on.
    private Kind placed;
    private BlockPos placedAt;
    private int placedTick;

    // The kind waiting to be collected. Null whilst nothing is.
    public Kind pending() {
        return placed;
    }

    public void reset() {
        forget();
    }

    // One tick of a fall that would hurt. The landing goes down once the ground is in
    // reach and the fall is slowed for a tick that would carry it past that point.
    public void fall(List<Kind> ranked, double fallen, boolean anchor) {
        LocalPlayer player = MC.player;
        double drop = Math.max(0, -player.getDeltaMovement().y);
        double reach = player.blockInteractionRange() - REACH_MARGIN;
        BlockHitResult ground = groundBelow(reach + drop);
        if (ground == null || alreadySoft(ground)) {
            loan.giveBack(loan.stillMine());
            return;
        }
        double feet = player.getY();
        double surface = ground.getLocation().y;
        if (fallen + feet - surface < player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE) + 1) {
            return;
        }
        // The highest the feet can be with the ground still in reach of the eyes.
        double highest = surface + reach - player.getEyeHeight();
        Plan plan = plan(ranked, ground, feet, highest);
        if (plan == null) {
            return;
        }
        if (feet <= highest) {
            put(plan, anchor);
        } else {
            brake(feet, drop, plan.lowest(), highest);
        }
    }

    // One tick with no fall to break. The water or snow goes back into a bucket once
    // the player stands on the ground or in the water.
    public void settle(boolean collect) {
        if (placed == null) {
            loan.giveBack(loan.stillMine());
            return;
        }
        int waited = MC.player.tickCount - placedTick;
        if (!collect || waited < 0 || waited > GIVE_UP) {
            forget();
            return;
        }
        if (!stillThere()) {
            if (waited > ECHO) {
                forget();
            }
            return;
        }
        if (waited > 0 && (MC.player.onGround() || MC.player.isInWater())) {
            collect();
        }
    }

    // The first landing in the player's order that is carried and fits here. Out of reach
    // any landing with a window to go down in will do because the brake gets the feet there.
    private static Plan plan(List<Kind> ranked, BlockHitResult ground, double feet, double highest) {
        boolean inReach = feet <= highest;
        for (Kind kind : ranked) {
            if (InventoryUtil.findSlot(kind.item, InventoryUtil.WHOLE_INVENTORY) == -1) {
                continue;
            }
            Plan plan = kind == Kind.WATER ? pourPlan(ground) : layPlan(kind, ground.getBlockPos());
            if (plan != null && plan.lowest() <= highest && (!inReach || feet >= plan.lowest())) {
                return plan;
            }
        }
        return null;
    }

    // Water boils away in the nether. Poured into a block you stand on top of it
    // catches nobody. Sneaking pours it into the space above instead. Water still
    // catches you in the tick you land.
    private static Plan pourPlan(BlockHitResult ground) {
        BlockPos pos = ground.getBlockPos();
        if (BlockUtil.waterEvaporates(pos)) {
            return null;
        }
        Plan plain = pourPlan(pos, false, ground.getLocation().y);
        return plain != null ? plain : pourPlan(pos, true, ground.getLocation().y);
    }

    private static Plan pourPlan(BlockPos ground, boolean sneak, double surface) {
        BlockPos spot = Buckets.pourSpot(ground, sneak);
        return spot != null && catches(spot, ground)
            ? new Plan(Kind.WATER, ground, spot, sneak, surface) : null;
    }

    // A block fills the ground when the ground gives way and sits on top of it
    // otherwise. The feet need a block of room above wherever it goes. A sneaking
    // player takes the whole fall on slime.
    private static Plan layPlan(Kind kind, BlockPos ground) {
        if (kind == Kind.SLIME_BLOCK && MC.player.isShiftKeyDown()) {
            return null;
        }
        BlockPos spot = BlockUtil.isReplaceable(ground) ? ground : ground.above();
        if (!BlockUtil.isReplaceable(spot)) {
            return null;
        }
        return new Plan(kind, ground, spot, BlockUtil.opensOnClick(BlockUtil.state(ground)),
            spot.getY() + ROOM);
    }

    // Water poured into the ground only catches a player who lands low enough to be in it.
    private static boolean catches(BlockPos water, BlockPos ground) {
        if (!water.equals(ground)) {
            return true;
        }
        VoxelShape shape = BlockUtil.state(ground).getCollisionShape(MC.level, ground,
            CollisionContext.of(MC.player));
        return shape.isEmpty() || shape.max(Direction.Axis.Y) < WATER_SURFACE;
    }

    // Brakes for one tick when the tick would carry the feet below the last point this
    // landing can go down. The feet then stop halfway into its window.
    private static void brake(double feet, double drop, double lowest, double highest) {
        if (feet - drop >= lowest) {
            return;
        }
        Vec3 velocity = MC.player.getDeltaMovement();
        MC.player.setDeltaMovement(velocity.x, (lowest + highest) / 2 - feet, velocity.z);
    }

    private void put(Plan plan, boolean anchor) {
        int slot = InventoryUtil.findSlot(plan.kind().item, InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1 || !loan.select(slot)) {
            return;
        }
        if (anchor) {
            centre();
        }
        if (plan.kind() == Kind.WATER) {
            pour(plan);
        } else {
            lay(plan);
        }
    }

    // Stops over the middle of the ground with no drift left. The landing then lies under
    // the whole body. The spot never leaves the block column the server raycasts down
    // and the move packet at the end of the tick carries it.
    private static void centre() {
        LocalPlayer player = MC.player;
        player.setPos(Mth.floor(player.getX()) + 0.5, player.getY(), Mth.floor(player.getZ()) + 0.5);
        player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
    }

    // The bucket raycasts from the view and its packet carries the angle. Looking
    // straight down for the call is enough. The spot that turned wet is where the
    // water really went.
    private void pour(Plan plan) {
        List<BlockPos> dry = Stream.of(plan.ground(), plan.ground().above())
            .filter(pos -> !BlockUtil.isWaterSource(pos)).toList();
        if (!RotationManager.whileFacing(MC.player.getYRot(), RotationManager.STRAIGHT_DOWN,
            () -> withSneak(plan, InputUtil::useMainHand))) {
            return;
        }
        for (BlockPos pos : dry) {
            if (BlockUtil.isWaterSource(pos)) {
                remember(Kind.WATER, pos);
                return;
            }
        }
        // A block that holds water only turns wet when the server's update arrives.
        if (plan.spot().equals(plan.ground())) {
            remember(Kind.WATER, plan.spot());
            return;
        }
        loan.giveBack(loan.stillMine());
    }

    // A click on the top of the ground. A powder snow bucket has no use of its own
    // and only places this way.
    private void lay(Plan plan) {
        if (!withSneak(plan, () -> BlockUtil.place(plan.ground().above(), Direction.DOWN, true, true))) {
            return;
        }
        if (plan.kind() == Kind.POWDER_SNOW && BlockUtil.state(plan.spot()).is(Blocks.POWDER_SNOW)) {
            remember(Kind.POWDER_SNOW, plan.spot());
        } else {
            loan.giveBack(loan.stillMine());
        }
    }

    private void remember(Kind kind, BlockPos pos) {
        placed = kind;
        placedAt = pos;
        placedTick = MC.player.tickCount;
    }

    // The bucket left in hand by the pour goes first. A miss is tried again next tick.
    private void collect() {
        if (Buckets.scoop(placedAt, loan)) {
            forget();
        }
    }

    private boolean stillThere() {
        return placed == Kind.WATER
            ? BlockUtil.isWaterSource(placedAt) : BlockUtil.state(placedAt).is(Blocks.POWDER_SNOW);
    }

    private void forget() {
        placed = null;
        placedAt = null;
        loan.giveBack(loan.stillMine());
    }

    // The first surface straight below the eyes. Water counts as one.
    private static BlockHitResult groundBelow(double range) {
        Vec3 eye = MC.player.getEyePosition();
        BlockHitResult hit = MC.level.clip(new ClipContext(eye, eye.subtract(0, range, 0),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.WATER, MC.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    // True when the fall already ends in water or a web or powder snow or slime. Webs and
    // powder snow hold you and the server forgets the fall. Slime cancels it unless you sneak.
    private static boolean alreadySoft(BlockHitResult ground) {
        BlockPos pos = ground.getBlockPos();
        FluidState fluid = MC.level.getFluidState(pos);
        if (fluid.is(FluidTags.WATER)
            && ground.getLocation().y <= pos.getY() + fluid.getHeight(MC.level, pos) + SURFACE_SLACK) {
            return true;
        }
        BlockState state = BlockUtil.state(pos);
        return state.is(Blocks.COBWEB) || state.is(Blocks.POWDER_SNOW)
            || (state.is(Blocks.SLIME_BLOCK) && !MC.player.isShiftKeyDown());
    }

    private static boolean withSneak(Plan plan, BooleanSupplier action) {
        return plan.sneak() ? InputUtil.whileSneaking(action) : action.getAsBoolean();
    }
}
