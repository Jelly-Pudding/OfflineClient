package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.module.RespawnBlockAura;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Only works where an anchor does not set a spawn point.
public final class AnchorAura extends RespawnBlockAura {

    private static final float HUE = 170;

    private final NumberSetting chargeDelay;
    private final NumberSetting detonateDelay;

    public AnchorAura() {
        this(new NumberSetting("Charge delay",
                "Ticks to wait after a blast before glowstone goes into the next anchor.", 2, 0, 20, 1, " ticks"),
            new NumberSetting("Detonate delay",
                "Ticks to wait after the glowstone before the anchor is set off.", 2, 0, 20, 1, " ticks"));
    }

    private AnchorAura(NumberSetting chargeDelay, NumberSetting detonateDelay) {
        super("AnchorAura", "Places respawn anchors near enemies and sets them off.", "anchors", HUE,
            List.of(), List.of(chargeDelay, detonateDelay));
        this.chargeDelay = chargeDelay;
        this.detonateDelay = detonateDelay;
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
        boolean charging = charge == 0 || forcedTopUp;
        // An empty hand sets a charged anchor off just as well.
        int slot = charging ? armGlowstone() : armOther("(hotbar is all glowstone)");
        if (slot == -1) {
            return true;
        }
        if (look(Vec3.atCenterOf(best)) && BlockUtil.interact(best, BlockUtil.facingSide(best))) {
            swing.getValue().use();
            holdBreak(charging ? detonateDelay.getInt() : chargeDelay.getInt());
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
            float damage = damageBeating(bestDamage, target, pos, pos);
            if (damage == 0) {
                continue;
            }
            bestDamage = damage;
            best = pos.immutable();
        }
        return best;
    }

    @Override
    protected boolean placeBest(LivingEntity target) {
        BlockPos best = bestSpot(target, null);
        plan(best);
        if (best == null) {
            status = "(no safe spot)";
            return false;
        }
        if (armAmmo("(no anchors)") == -1 || !look(Vec3.atCenterOf(best))) {
            return true;
        }
        // The rotation has already been asked for. Placing must not ask again.
        if (BlockUtil.placeAny(best, false, false)) {
            swing.getValue().use();
            placed();
        }
        return true;
    }

    @Override
    protected boolean spotWithout(LivingEntity target, BlockPos gone) {
        return bestSpot(target, gone) != null;
    }

    // Where an anchor would hurt the target most. The block named as gone counts as mined out.
    private BlockPos bestSpot(LivingEntity target, BlockPos gone) {
        BlockPos best = null;
        float bestDamage = 0;
        for (BlockPos pos : nearby(target)) {
            if (!placeable(pos, gone) || !inReach(pos, true)
                || !BlockUtil.unobstructed(pos, Blocks.RESPAWN_ANCHOR.defaultBlockState())) {
                continue;
            }
            float damage = damageBeating(bestDamage, target, pos, pos, gone);
            if (damage == 0) {
                continue;
            }
            bestDamage = damage;
            best = pos.immutable();
        }
        return best;
    }
}
