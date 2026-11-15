package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.module.RespawnBlockAura;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.Holes;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Only works where a bed explodes instead of letting anyone sleep.
public final class BedAura extends RespawnBlockAura {

    private static final float HUE = 170;

    // Every bed has the same shape. This one stands in when a spot is tested for room.
    private static final BlockState BED = Blocks.BED.red().defaultBlockState();

    // Where a bed fits with the direction its head would point.
    private record Spot(BlockPos foot, Direction facing) {
    }

    private final BoolSetting roofFirst;
    private final NumberSetting breakDelay;
    private final BoolSetting strictDirection = new BoolSetting("Strict direction",
        "Only lays beds along the way you face or straight back from it.", false);

    public BedAura() {
        this(new BoolSetting("Roof first",
                "Puts a blast proof block over the target's head before the beds to stop them jumping out.", false),
            new NumberSetting("Break delay", "Ticks to wait between detonations.", 2, 0, 20, 1, " ticks"));
    }

    private BedAura(BoolSetting roofFirst, NumberSetting breakDelay) {
        super("BedAura", "Places beds near enemies and sets them off.", "beds", HUE,
            List.of(roofFirst), List.of(breakDelay));
        this.roofFirst = roofFirst;
        this.breakDelay = breakDelay;
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
            float damage = damageBeating(bestDamage, target, head, pos, head);
            if (damage == 0) {
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
            swing.getValue().use();
            holdBreak(breakDelay.getInt());
        }
        return true;
    }

    @Override
    protected boolean placeBest(LivingEntity target) {
        if (roofFirst.isOn() && roofOver(target)) {
            return true;
        }
        Spot spot = bestSpot(target, null);
        if (spot == null) {
            plan();
            status = "(no safe spot)";
            return false;
        }
        plan(spot.foot(), spot.foot().relative(spot.facing()));
        if (armAmmo("(no beds)") == -1) {
            return true;
        }
        if (!faceAlong(spot.facing(), Vec3.atCenterOf(spot.foot()))) {
            status = "(turning)";
            return true;
        }
        if (layBed(spot)) {
            swing.getValue().use();
            placed();
        }
        return true;
    }

    @Override
    protected boolean spotWithout(LivingEntity target, BlockPos gone) {
        return bestSpot(target, gone) != null;
    }

    // Where a bed would hurt the target most. The block named as gone counts as mined out.
    // The game tests the foot half for room and only asks that the head half lands on
    // something it can replace. The head half may lie inside the target.
    private Spot bestSpot(LivingEntity target, BlockPos gone) {
        Spot best = null;
        float bestDamage = 0;
        for (BlockPos foot : nearby(target)) {
            if (!placeable(foot, gone) || !BlockUtil.unobstructed(foot, BED) || !inReach(foot, true)) {
                continue;
            }
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                if (!allowedFacing(facing)) {
                    continue;
                }
                BlockPos head = foot.relative(facing);
                if (!head.equals(gone) && !BlockUtil.isReplaceable(head)) {
                    continue;
                }
                float damage = damageBeating(bestDamage, target, head, foot, head, gone);
                if (damage == 0) {
                    continue;
                }
                bestDamage = damage;
                best = new Spot(foot.immutable(), facing);
            }
        }
        return best;
    }

    // Caps the target. True whilst the roof takes the tick.
    private boolean roofOver(LivingEntity target) {
        BlockPos over = Holes.headBlock(target).above();
        if (!BlockUtil.blockFits(over) || !inReach(over, true)) {
            return false;
        }
        int slot = BlockUtil.findBlastProofSlot();
        if (slot == -1 || armSlot(slot) == -1) {
            return false;
        }
        if (look(Vec3.atCenterOf(over)) && BlockUtil.placeAny(over, false, false)) {
            swing.getValue().use();
            placed();
        }
        return true;
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
        // The rotation has already been asked for. Placing must not ask again.
        return RotationManager.whileFacing(spot.facing().toYRot(), mc.player.getXRot(),
            () -> BlockUtil.placeAny(spot.foot(), false, false));
    }

    // Vanilla blows a bed up from the head half.
    private BlockPos headOf(BlockPos pos, BlockState state) {
        if (state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return pos;
        }
        return pos.relative(state.getValue(HorizontalDirectionalBlock.FACING));
    }

    // Bed direction comes from the yaw held server side when the click lands.
    // Waits for the requested turn to actually be sent before placing.
    private boolean faceAlong(Direction facing, Vec3 point) {
        return faceTarget.getValue().faceExact(facing.toYRot(),
            RotationManager.pitchTo(point), RotationPriority.AURA);
    }
}
