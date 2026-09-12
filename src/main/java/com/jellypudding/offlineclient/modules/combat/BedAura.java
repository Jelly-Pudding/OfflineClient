package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.module.RespawnBlockAura;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

// Only works where a bed explodes instead of letting anyone sleep.
public final class BedAura extends RespawnBlockAura {

    private static final float HUE = 170;

    // Where a bed fits with the direction its head would point.
    private record Spot(BlockPos foot, Direction facing) {
    }

    private final BoolSetting strictDirection = new BoolSetting("Strict direction",
        "Only lays beds along the way you face or straight back from it.", false);

    public BedAura() {
        super("BedAura", "Places beds near enemies and sets them off.", "beds", HUE);
        addSettings(strictDirection);
        searchTags("bed bomb", "nether", "end", "cpvp");
    }

    @Override
    protected boolean explodesHere() {
        return ExplosionUtil.bedsExplodeHere();
    }

    @Override
    protected String safeHereStatus() {
        return "(beds are safe here)";
    }

    @Override
    protected boolean isAmmo(ItemStack stack) {
        return stack.is(ItemTags.BEDS) && stack.getItem() instanceof BlockItem;
    }

    // Right clicks the bed in reach that would hurt the target most.
    @Override
    protected boolean detonateBest(LivingEntity target) {
        BlockPos best = null;
        float bestDamage = 0;
        for (BlockPos pos : nearby(target)) {
            BlockState state = BlockUtil.state(pos);
            if (!(state.getBlock() instanceof BedBlock) || !inReach(pos, false)) {
                continue;
            }
            BlockPos head = headOf(pos, state);
            Vec3 centre = Vec3.atCenterOf(head);
            float damage = ExplosionUtil.blastDamage(target, centre, ExplosionUtil.RESPAWN_BLOCK_POWER,
                Vec3.ZERO, pos, head);
            if (!worthIt(damage) || damage <= bestDamage || !selfSafe(centre, pos, head)) {
                continue;
            }
            bestDamage = damage;
            best = pos.immutable();
        }
        if (best == null) {
            return false;
        }
        arm(best);
        if (!breakReady() || !look(Vec3.atCenterOf(best))) {
            return true;
        }
        if (BlockUtil.interact(best, Direction.UP)) {
            swing.getValue().swing(InteractionHand.MAIN_HAND);
            fired();
        }
        return true;
    }

    @Override
    protected void placeBest(LivingEntity target) {
        int slot = armAmmo("(no beds)");
        if (slot == -1) {
            return;
        }
        Block bed = ((BlockItem) mc.player.getInventory().getItem(slot).getItem()).getBlock();
        Spot spot = bestSpot(target, bed);
        if (spot == null) {
            plan();
            status = "(no safe spot)";
            return;
        }
        plan(spot.foot(), spot.foot().relative(spot.facing()));
        if (!faceAlong(spot.facing(), Vec3.atCenterOf(spot.foot()))) {
            status = "(turning)";
            return;
        }
        if (layBed(spot)) {
            swing.getValue().swing(InteractionHand.MAIN_HAND);
            placed();
        }
    }

    private Spot bestSpot(LivingEntity target, Block bed) {
        Spot best = null;
        float bestDamage = 0;
        for (BlockPos foot : nearby(target)) {
            if (!free(foot, bed) || !placeable(foot) || !inReach(foot, true)) {
                continue;
            }
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                if (!allowedFacing(facing)) {
                    continue;
                }
                BlockPos head = foot.relative(facing);
                if (!free(head, bed)) {
                    continue;
                }
                Vec3 centre = Vec3.atCenterOf(head);
                float damage = ExplosionUtil.blastDamage(target, centre, ExplosionUtil.RESPAWN_BLOCK_POWER,
                    Vec3.ZERO, foot, head);
                if (!worthIt(damage) || damage <= bestDamage || !selfSafe(centre, foot, head)) {
                    continue;
                }
                bestDamage = damage;
                best = new Spot(foot.immutable(), facing);
            }
        }
        return best;
    }

    // Without a rotation the bed can only follow the real facing. Strict keeps to that axis.
    private boolean allowedFacing(Direction facing) {
        Direction own = mc.player.getDirection();
        if (faceTarget.is(FaceMode.OFF)) {
            return facing == own;
        }
        return !strictDirection.isOn() || facing.getAxis() == own.getAxis();
    }

    private boolean layBed(Spot spot) {
        // The bed follows the yaw the client holds when the placement runs.
        float heldYaw = mc.player.getYRot();
        mc.player.setYRot(spot.facing().toYRot());
        try {
            // The rotation has already been asked for. Placing must not ask again.
            return BlockUtil.placeAny(spot.foot(), false, false);
        } finally {
            mc.player.setYRot(heldYaw);
        }
    }

    // Vanilla blows a bed up from the head half.
    private BlockPos headOf(BlockPos pos, BlockState state) {
        if (state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return pos;
        }
        return pos.relative(state.getValue(HorizontalDirectionalBlock.FACING));
    }

    private boolean free(BlockPos pos, Block bed) {
        return BlockUtil.isReplaceable(pos)
            && mc.level.isUnobstructed(bed.defaultBlockState(), pos, CollisionContext.empty());
    }

    // Bed direction comes from the yaw held server side when the click lands.
    // Waits for the requested turn to actually be sent before placing.
    private boolean faceAlong(Direction facing, Vec3 point) {
        return faceTarget.getValue().faceExact(facing.toYRot(),
            RotationManager.pitchTo(point), RotationPriority.AURA);
    }
}
