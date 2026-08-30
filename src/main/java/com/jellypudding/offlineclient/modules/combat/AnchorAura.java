package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

// Only works where an anchor does not set a spawn point.
public final class AnchorAura extends Module {

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 10, 2, 16, 0.5, " blocks");
    private final NumberSetting range = new NumberSetting("Range",
        "Reach for placing and using anchors.", 4.5, 1, 6, 0.1);
    private final BoolSetting doPlace = new BoolSetting("Place",
        "Place anchors near the target.", true);
    private final NumberSetting placeDelay = new NumberSetting("Place delay",
        "Ticks to wait between placements.", 3, 0, 20, 1, " ticks");
    private final BoolSetting doBreak = new BoolSetting("Break",
        "Charge anchors and set them off.", true);
    private final NumberSetting chargeDelay = new NumberSetting("Charge delay",
        "Ticks to wait between glowstone charges.", 1, 0, 20, 1, " ticks");
    private final NumberSetting breakDelay = new NumberSetting("Break delay",
        "Ticks to wait between detonations.", 2, 0, 20, 1, " ticks");
    private final NumberSetting minDamage = new NumberSetting("Min damage",
        "Only act when the enemy would take at least this much.", 6, 0, 20, 0.5);
    private final NumberSetting maxSelfDamage = new NumberSetting("Max self damage",
        "Never take more than this from your own anchor.", 8, 0, 20, 0.5);
    private final BoolSetting antiSuicide = new BoolSetting("Anti suicide",
        "Never set off an anchor that could kill you.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward the anchor.", true);
    private final BoolSetting render = new BoolSetting("Show placement",
        "Outline the spot the next anchor goes in.", true);

    private int placeTimer;
    private int chargeTimer;
    private int breakTimer;
    private final SlotSwap slots = new SlotSwap();
    private BlockPos planned;
    private BlockPos armed;
    private String targetName;
    private String status;

    public AnchorAura() {
        super("AnchorAura", "Places respawn anchors near enemies and sets them off.", Category.COMBAT);
        addSettings(targetRange, range, doPlace, placeDelay, doBreak, chargeDelay, breakDelay,
            minDamage, maxSelfDamage, antiSuicide, rotate, render);
        searchTags("respawn anchor", "glowstone", "nether", "cpvp");
    }

    @Override
    public String getSuffix() {
        return suffix(targetName, status);
    }

    @Override
    protected void onEnable() {
        placeTimer = 0;
        chargeTimer = 0;
        breakTimer = 0;
        slots.forget();
        planned = null;
        armed = null;
        targetName = null;
        status = null;
    }

    @Override
    protected void onDisable() {
        slots.restore();
        planned = null;
        armed = null;
        targetName = null;
        status = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        planned = null;
        armed = null;
        status = null;
        if (!inGame() || mc.player.isSpectator()) {
            targetName = null;
            return;
        }
        if (placeTimer > 0) {
            placeTimer--;
        }
        if (chargeTimer > 0) {
            chargeTimer--;
        }
        if (breakTimer > 0) {
            breakTimer--;
        }

        if (!explodesHere()) {
            targetName = null;
            status = "(anchors are safe here)";
            return;
        }

        Player target = EntityUtil.nearestEnemy(targetRange.getValue());
        targetName = EntityUtil.nameOf(target);
        if (target == null) {
            slots.restore();
            return;
        }
        if (mc.player.isUsingItem()) {
            slots.restore();
            status = "(paused)";
            return;
        }

        boolean acted = doBreak.isOn() && useBest(target);
        if (!acted && doPlace.isOn() && placeTimer == 0) {
            placeBest(target);
        }
        slots.restore();
    }

    private boolean explodesHere() {
        return ExplosionUtil.anchorsExplodeHere();
    }

    /**
     * Charges the best anchor in reach or sets it off once it holds a charge.
     * True whenever an anchor worth using is already there.
     */
    private boolean useBest(Player target) {
        BlockPos best = bestAnchor(target);
        if (best == null) {
            return false;
        }
        armed = best;

        BlockState state = BlockUtil.state(best);
        int charge = state.getValue(RespawnAnchorBlock.CHARGE);
        // Vanilla hands the click to the other hand whilst that hand holds fuel
        // and the anchor is not full.
        boolean forcedTopUp = charge < RespawnAnchorBlock.MAX_CHARGES
            && mc.player.getOffhandItem().is(Items.GLOWSTONE);
        if (charge == 0 || forcedTopUp) {
            if (chargeTimer > 0) {
                return true;
            }
            int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.GLOWSTONE));
            if (slot == -1) {
                status = "(no glowstone)";
                return true;
            }
            slots.select(slot);
            if (interact(best)) {
                chargeTimer = chargeDelay.getInt();
            }
            return true;
        }

        if (breakTimer > 0) {
            return true;
        }
        // An empty hand sets a charged anchor off just as well.
        int slot = InventoryUtil.hotbarSlot(stack -> !stack.is(Items.GLOWSTONE));
        if (slot == -1) {
            status = "(hotbar is all glowstone)";
            return true;
        }
        slots.select(slot);
        if (interact(best)) {
            breakTimer = breakDelay.getInt();
        }
        return true;
    }

    private BlockPos bestAnchor(Player target) {
        BlockPos best = null;
        float bestDamage = 0;
        for (BlockPos pos : nearby(target)) {
            if (BlockUtil.state(pos).getBlock() != Blocks.RESPAWN_ANCHOR) {
                continue;
            }
            if (BlockUtil.distanceTo(pos) > range.getValue()) {
                continue;
            }
            Vec3 center = Vec3.atCenterOf(pos);
            float damage = ExplosionUtil.blastDamage(target, center, ExplosionUtil.RESPAWN_BLOCK_POWER, Vec3.ZERO, pos);
            if (damage < minDamage.getFloat() || damage <= bestDamage || !selfSafe(center, pos)) {
                continue;
            }
            bestDamage = damage;
            best = pos.immutable();
        }
        return best;
    }

    private void placeBest(Player target) {
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.RESPAWN_ANCHOR));
        if (slot == -1) {
            status = "(no anchors)";
            return;
        }
        BlockPos best = null;
        float bestDamage = 0;
        for (BlockPos pos : nearby(target)) {
            if (!BlockUtil.isReplaceable(pos) || BlockUtil.distanceTo(pos) > range.getValue()) {
                continue;
            }
            if (!mc.level.isUnobstructed(Blocks.RESPAWN_ANCHOR.defaultBlockState(), pos,
                CollisionContext.empty())) {
                continue;
            }
            Vec3 center = Vec3.atCenterOf(pos);
            float damage = ExplosionUtil.blastDamage(target, center, ExplosionUtil.RESPAWN_BLOCK_POWER, Vec3.ZERO, pos);
            if (damage < minDamage.getFloat() || damage <= bestDamage || !selfSafe(center, pos)) {
                continue;
            }
            bestDamage = damage;
            best = pos.immutable();
        }
        planned = best;
        if (best == null) {
            status = "(no safe spot)";
            return;
        }

        if (rotate.isOn() && !RotationManager.look(Vec3.atCenterOf(best), RotationPriority.AURA,
            RotationManager.BLOCK_TOLERANCE)) {
            status = "(turning)";
            return;
        }

        slots.select(slot);
        // The turn above already picked the angle.
        boolean placed = BlockUtil.placeAny(best, false, true);
        if (placed) {
            placeTimer = placeDelay.getInt();
        }
    }

    private Iterable<BlockPos> nearby(Player target) {
        return BlockUtil.positionsAround(target.blockPosition(), (int) Math.ceil(range.getValue()));
    }

    private boolean interact(BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        if (rotate.isOn() && !RotationManager.look(center, RotationPriority.AURA,
            RotationManager.BLOCK_TOLERANCE)) {
            status = "(turning)";
            return false;
        }
        return BlockUtil.interact(pos, BlockUtil.facingSide(pos));
    }

    private boolean selfSafe(Vec3 source, BlockPos anchor) {
        return ExplosionUtil.selfSafe(source, ExplosionUtil.RESPAWN_BLOCK_POWER,
            maxSelfDamage.getFloat(), antiSuicide.isOn(), anchor);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        if (planned != null) {
            event.getBatch().outlineBlock(planned, 0xFF40FFD0, false);
        }
        if (armed != null) {
            event.getBatch().outlineBlock(armed, 0xFFFF4040, false);
        }
    }
}
