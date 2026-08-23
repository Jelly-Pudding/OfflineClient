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
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.player.Player;
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
public final class BedAura extends Module {

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 8, 2, 16, 0.5, " blocks");
    private final NumberSetting range = new NumberSetting("Range",
        "Reach for placing and using beds.", 4.5, 1, 6, 0.1);
    private final BoolSetting doPlace = new BoolSetting("Place",
        "Place beds near the target.", true);
    private final NumberSetting placeDelay = new NumberSetting("Place delay",
        "Ticks to wait between placements.", 4, 0, 20, 1, " ticks");
    private final BoolSetting doBreak = new BoolSetting("Break",
        "Set off beds that are already down.", true);
    private final NumberSetting breakDelay = new NumberSetting("Break delay",
        "Ticks to wait between detonations.", 2, 0, 20, 1, " ticks");
    private final NumberSetting minDamage = new NumberSetting("Min damage",
        "Only act when the enemy would take at least this much.", 6, 0, 20, 0.5);
    private final NumberSetting maxSelfDamage = new NumberSetting("Max self damage",
        "Never take more than this from your own bed.", 8, 0, 20, 0.5);
    private final BoolSetting antiSuicide = new BoolSetting("Anti suicide",
        "Never set off a bed that could kill you.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet to lay the bed the way you want.", true);
    private final BoolSetting render = new BoolSetting("Show placement",
        "Outline the two blocks the next bed fills.", true);

    private int placeTimer;
    private int breakTimer;
    private final SlotSwap slots = new SlotSwap();
    private BlockPos plannedFoot;
    private BlockPos plannedHead;
    private BlockPos armed;
    private String targetName;
    private String status;

    public BedAura() {
        super("BedAura", "Places beds near enemies and sets them off.", Category.COMBAT);
        addSettings(targetRange, range, doPlace, placeDelay, doBreak, breakDelay,
            minDamage, maxSelfDamage, antiSuicide, rotate, render);
        searchTags("bed bomb", "nether", "end", "cpvp");
    }

    @Override
    public String getSuffix() {
        return suffix(targetName, status);
    }

    @Override
    protected void onEnable() {
        placeTimer = 0;
        breakTimer = 0;
        slots.forget();
        plannedFoot = null;
        plannedHead = null;
        armed = null;
        targetName = null;
        status = null;
    }

    @Override
    protected void onDisable() {
        slots.restore();
        plannedFoot = null;
        plannedHead = null;
        armed = null;
        targetName = null;
        status = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        plannedFoot = null;
        plannedHead = null;
        armed = null;
        status = null;
        if (!inGame() || mc.player.isSpectator()) {
            targetName = null;
            return;
        }
        if (placeTimer > 0) {
            placeTimer--;
        }
        if (breakTimer > 0) {
            breakTimer--;
        }

        if (!explodesHere()) {
            targetName = null;
            status = "(beds are safe here)";
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

        boolean acted = doBreak.isOn() && detonateBest(target);
        if (!acted && doPlace.isOn() && placeTimer == 0) {
            placeBest(target);
        }
        slots.restore();
    }

    private boolean explodesHere() {
        BedRule rule = mc.level.environmentAttributes()
            .getValue(EnvironmentAttributes.BED_RULE, mc.player.blockPosition());
        return rule.explodes();
    }

    /**
     * Right clicks the bed in reach that would hurt the target most.
     * True whenever such a bed is already there.
     */
    private boolean detonateBest(Player target) {
        BlockPos best = null;
        float bestDamage = 0;
        for (BlockPos pos : nearby(target)) {
            BlockState state = BlockUtil.state(pos);
            if (!(state.getBlock() instanceof BedBlock)) {
                continue;
            }
            if (BlockUtil.distanceTo(pos) > range.getValue()) {
                continue;
            }
            Vec3 center = Vec3.atCenterOf(headOf(pos, state));
            float damage = ExplosionUtil.blastDamage(target, center, ExplosionUtil.RESPAWN_BLOCK_POWER);
            // The self check raycasts.
            if (damage < minDamage.getFloat() || damage <= bestDamage || !selfSafe(center)) {
                continue;
            }
            bestDamage = damage;
            best = pos.immutable();
        }
        if (best == null) {
            return false;
        }
        armed = best;
        if (breakTimer > 0) {
            return true;
        }

        Vec3 hit = Vec3.atCenterOf(best);
        if (rotate.isOn() && !RotationManager.look(hit, RotationPriority.AURA,
            RotationManager.BLOCK_TOLERANCE)) {
            status = "(turning)";
            return true;
        }
        if (BlockUtil.interact(best, Direction.UP)) {
            breakTimer = breakDelay.getInt();
        }
        return true;
    }

    // Where a bed fits with the direction its head would point.
    private record Spot(BlockPos foot, Direction facing) {
    }

    private void placeBest(Player target) {
        int slot = bedSlot();
        if (slot == -1) {
            status = "(no beds)";
            return;
        }
        Block bed = ((BlockItem) mc.player.getInventory().getItem(slot).getItem()).getBlock();
        Spot spot = bestSpot(target, bed);
        plannedFoot = spot == null ? null : spot.foot();
        plannedHead = spot == null ? null : spot.foot().relative(spot.facing());
        if (spot == null) {
            status = "(no safe spot)";
            return;
        }
        if (rotate.isOn() && !faceAlong(spot.facing(), Vec3.atCenterOf(spot.foot()))) {
            status = "(turning)";
            return;
        }

        slots.select(slot);
        if (layBed(spot)) {
            placeTimer = placeDelay.getInt();
        }
    }

    private Spot bestSpot(Player target, Block bed) {
        Spot best = null;
        float bestDamage = 0;
        for (BlockPos foot : nearby(target)) {
            if (!free(foot, bed) || BlockUtil.distanceTo(foot) > range.getValue()) {
                continue;
            }
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                if (!rotate.isOn() && facing != mc.player.getDirection()) {
                    continue;
                }
                BlockPos head = foot.relative(facing);
                if (!free(head, bed)) {
                    continue;
                }
                Vec3 center = Vec3.atCenterOf(head);
                float damage = ExplosionUtil.blastDamage(target, center, ExplosionUtil.RESPAWN_BLOCK_POWER);
                if (damage < minDamage.getFloat() || damage <= bestDamage || !selfSafe(center)) {
                    continue;
                }
                bestDamage = damage;
                best = new Spot(foot.immutable(), facing);
            }
        }
        return best;
    }

    private boolean layBed(Spot spot) {
        Direction support = BlockUtil.findPlaceSupport(spot.foot());
        // The bed follows the yaw the client holds when the placement runs.
        float heldYaw = mc.player.getYRot();
        mc.player.setYRot(spot.facing().toYRot());
        try {
            // The turn above already picked the direction.
            return support != null
                ? BlockUtil.place(spot.foot(), support, false, true)
                : BlockUtil.placeDirect(spot.foot(), false, true);
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

    private int bedSlot() {
        return InventoryUtil.hotbarSlot(BedAura::isBed);
    }

    private static boolean isBed(ItemStack stack) {
        return stack.is(ItemTags.BEDS) && stack.getItem() instanceof BlockItem;
    }

    /**
     * Asks for the exact yaw that lays a bed along the side. A yaw a step short
     * of the target turns the bed a quarter of the way round.
     */
    private boolean faceAlong(Direction facing, Vec3 point) {
        float yaw = facing.toYRot();
        float pitch = RotationManager.pitchTo(point);
        RotationManager.requestExact(yaw, pitch, RotationPriority.AURA);
        return RotationManager.isFacing(yaw, pitch, RotationManager.BLOCK_TOLERANCE);
    }

    private Iterable<BlockPos> nearby(Player target) {
        return BlockUtil.positionsAround(target.blockPosition(), (int) Math.ceil(range.getValue()));
    }

    private boolean selfSafe(Vec3 source) {
        return ExplosionUtil.selfSafe(source, ExplosionUtil.RESPAWN_BLOCK_POWER,
            maxSelfDamage.getFloat(), antiSuicide.isOn());
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        if (plannedFoot != null) {
            event.getBatch().outlineBlock(plannedFoot, 0xFF40FFD0, false);
        }
        if (plannedHead != null) {
            event.getBatch().outlineBlock(plannedHead, 0xFF40FFD0, false);
        }
        if (armed != null) {
            event.getBatch().outlineBlock(armed, 0xFFFF4040, false);
        }
    }
}
