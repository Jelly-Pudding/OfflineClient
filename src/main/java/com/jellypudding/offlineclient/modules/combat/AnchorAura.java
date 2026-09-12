package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.module.RespawnBlockAura;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

// Only works where an anchor does not set a spawn point.
public final class AnchorAura extends RespawnBlockAura {

    private static final float HUE = 170;

    public AnchorAura() {
        super("AnchorAura", "Places respawn anchors near enemies and sets them off.", "anchors", HUE);
        searchTags("respawn anchor", "glowstone", "nether", "cpvp");
    }

    @Override
    protected boolean explodesHere() {
        return ExplosionUtil.anchorsExplodeHere();
    }

    @Override
    protected String safeHereStatus() {
        return "(anchors are safe here)";
    }

    @Override
    protected boolean isAmmo(ItemStack stack) {
        return stack.is(Items.RESPAWN_ANCHOR);
    }

    // Charges the best anchor in reach or sets it off once it is charged.
    @Override
    protected boolean detonateBest(LivingEntity target) {
        BlockPos best = bestAnchor(target);
        if (best == null) {
            return false;
        }
        arm(best);
        if (!breakReady()) {
            return true;
        }
        BlockState state = BlockUtil.state(best);
        int charge = state.getValue(RespawnAnchorBlock.CHARGE);
        // Vanilla hands the click to the other hand whilst that hand holds fuel
        // and the anchor is not full.
        boolean forcedTopUp = charge < RespawnAnchorBlock.MAX_CHARGES
            && mc.player.getOffhandItem().is(Items.GLOWSTONE);
        int slot;
        if (charge == 0 || forcedTopUp) {
            slot = armGlowstone();
        } else {
            // An empty hand sets a charged anchor off just as well.
            slot = armOther("(hotbar is all glowstone)");
        }
        if (slot == -1) {
            return true;
        }
        if (look(Vec3.atCenterOf(best)) && BlockUtil.interact(best, BlockUtil.facingSide(best))) {
            swing.getValue().swing(InteractionHand.MAIN_HAND);
            fired();
        }
        return true;
    }

    private int armGlowstone() {
        return armItem(stack -> stack.is(Items.GLOWSTONE), "(no glowstone)");
    }

    private BlockPos bestAnchor(LivingEntity target) {
        BlockPos best = null;
        float bestDamage = 0;
        for (BlockPos pos : nearby(target)) {
            if (BlockUtil.state(pos).getBlock() != Blocks.RESPAWN_ANCHOR || !inReach(pos, false)) {
                continue;
            }
            Vec3 centre = Vec3.atCenterOf(pos);
            float damage = ExplosionUtil.blastDamage(target, centre, ExplosionUtil.RESPAWN_BLOCK_POWER,
                Vec3.ZERO, pos);
            if (!worthIt(damage) || damage <= bestDamage || !selfSafe(centre, pos)) {
                continue;
            }
            bestDamage = damage;
            best = pos.immutable();
        }
        return best;
    }

    @Override
    protected void placeBest(LivingEntity target) {
        BlockPos best = null;
        float bestDamage = 0;
        for (BlockPos pos : nearby(target)) {
            if (!placeable(pos) || !inReach(pos, true)) {
                continue;
            }
            if (!mc.level.isUnobstructed(Blocks.RESPAWN_ANCHOR.defaultBlockState(), pos,
                CollisionContext.empty())) {
                continue;
            }
            Vec3 centre = Vec3.atCenterOf(pos);
            float damage = ExplosionUtil.blastDamage(target, centre, ExplosionUtil.RESPAWN_BLOCK_POWER,
                Vec3.ZERO, pos);
            if (!worthIt(damage) || damage <= bestDamage || !selfSafe(centre, pos)) {
                continue;
            }
            bestDamage = damage;
            best = pos.immutable();
        }
        plan(best);
        if (best == null) {
            status = "(no safe spot)";
            return;
        }
        if (armAmmo("(no anchors)") == -1 || !look(Vec3.atCenterOf(best))) {
            return;
        }
        // The rotation has already been asked for. Placing must not ask again.
        if (BlockUtil.placeAny(best, false, false)) {
            swing.getValue().swing(InteractionHand.MAIN_HAND);
            placed();
        }
    }
}
