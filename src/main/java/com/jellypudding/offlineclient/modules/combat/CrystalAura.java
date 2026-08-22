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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Places end crystals next to enemies and detonates them. Every
 * candidate spot is scored with the game's own explosion math.
 */
public final class CrystalAura extends Module {

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 10, 2, 16, 0.5, " blocks");
    private final NumberSetting range = new NumberSetting("Range",
        "Reach for placing and hitting crystals.", 4.5, 1, 6, 0.1).min(1);
    private final NumberSetting wallsRange = new NumberSetting("Walls range",
        "Reach for crystals hidden behind blocks.", 3.5, 0, 6, 0.1);
    private final BoolSetting doPlace = new BoolSetting("Place",
        "Place crystals near the target.", true);
    private final NumberSetting placeDelay = new NumberSetting("Place delay",
        "Ticks to wait between placements.", 2, 0, 10, 1, " ticks");
    private final BoolSetting doBreak = new BoolSetting("Break",
        "Hit placed crystals so they explode.", true);
    private final NumberSetting breakDelay = new NumberSetting("Break delay",
        "Ticks to wait between hits.", 1, 0, 10, 1, " ticks");
    private final NumberSetting minDamage = new NumberSetting("Min damage",
        "Only act when the enemy would take at least this much.", 6, 0, 20, 0.5);
    private final NumberSetting maxSelfDamage = new NumberSetting("Max self damage",
        "Never take more than this from your own crystal.", 8, 0, 20, 0.5);
    private final BoolSetting antiSuicide = new BoolSetting("Anti suicide",
        "Never touch a crystal that could kill you.", true);
    private final BoolSetting oldPlacement = new BoolSetting("Old placement",
        "Require two air blocks above the base like older servers.", false);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward the crystal spot.", true);
    private final BoolSetting render = new BoolSetting("Show placement",
        "Outline the base block the next crystal goes on.", true);

    private int placeTimer;
    private int breakTimer;
    private int previousSlot = -1;
    private BlockPos planned;
    private String targetName;

    public CrystalAura() {
        super("CrystalAura", "Places end crystals near enemies and blows them up.", Category.COMBAT);
        addSettings(targetRange, range, wallsRange, doPlace, placeDelay, doBreak, breakDelay,
            minDamage, maxSelfDamage, antiSuicide, oldPlacement, rotate, render);
        searchTags("end crystal", "cpvp", "ca");
    }

    @Override
    public String getSuffix() {
        return targetName;
    }

    @Override
    protected void onEnable() {
        placeTimer = 0;
        breakTimer = 0;
        previousSlot = -1;
        planned = null;
        targetName = null;
    }

    @Override
    protected void onDisable() {
        restoreSlot();
        planned = null;
        targetName = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        planned = null;
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (placeTimer > 0) {
            placeTimer--;
        }
        if (breakTimer > 0) {
            breakTimer--;
        }

        Player target = EntityUtil.nearestEnemy(targetRange.getValue());
        targetName = target == null ? null : target.getGameProfile().name();
        if (target == null) {
            restoreSlot();
            return;
        }

        // Break before placing.
        if (doBreak.isOn() && breakTimer == 0) {
            breakBest(target);
        }
        if (doPlace.isOn() && placeTimer == 0) {
            placeBest(target);
        }
        restoreSlot();
    }

    /** Hits the crystal already in the world that hurts the target most. */
    private void breakBest(Player target) {
        EndCrystal best = null;
        float bestDamage = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal crystal) || crystal.isRemoved()) {
                continue;
            }
            Vec3 center = crystal.getBoundingBox().getCenter();
            double reach = canSee(center) ? range.getValue() : wallsRange.getValue();
            if (EntityUtil.reachDistance(mc.player, crystal) > reach) {
                continue;
            }
            if (!selfSafe(crystal.position())) {
                continue;
            }
            float damage = ExplosionUtil.crystalDamage(target, crystal.position());
            if (damage >= minDamage.getFloat() && damage > bestDamage) {
                bestDamage = damage;
                best = crystal;
            }
        }
        if (best == null) {
            return;
        }
        if (rotate.isOn()) {
            BlockUtil.faceVector(best.getBoundingBox().getCenter());
        }
        mc.player.connection.send(new ServerboundAttackPacket(best.getId()));
        mc.player.swing(InteractionHand.MAIN_HAND);
        breakTimer = breakDelay.getInt();
    }

    /** Places a crystal on the base block with the best damage score. */
    private void placeBest(Player target) {
        BlockPos base = bestBase(target);
        planned = base;
        if (base == null) {
            return;
        }
        InteractionHand hand = crystalHand();
        if (hand == null) {
            return;
        }
        Vec3 hit = Vec3.atCenterOf(base).add(0, 0.5, 0);
        if (rotate.isOn()) {
            BlockUtil.faceVector(hit);
        }
        BlockHitResult result = new BlockHitResult(hit, Direction.UP, base, false);
        if (mc.gameMode.useItemOn(mc.player, hand, result).consumesAction()) {
            mc.player.swing(hand);
            placeTimer = placeDelay.getInt();
        }
    }

    /** Scans around the target for the base block worth a crystal. */
    private BlockPos bestBase(Player target) {
        BlockPos feet = target.blockPosition();
        BlockPos best = null;
        float bestDamage = 0;
        for (BlockPos base : BlockPos.betweenClosed(feet.offset(-4, -3, -4), feet.offset(4, 3, 4))) {
            BlockPos above = base.above();
            if (!BlockUtil.state(above).isAir()) {
                continue;
            }
            Block block = BlockUtil.state(base).getBlock();
            if (block != Blocks.OBSIDIAN && block != Blocks.BEDROCK) {
                continue;
            }
            if (oldPlacement.isOn() && !BlockUtil.state(above.above()).isAir()) {
                continue;
            }

            Vec3 crystalPos = Vec3.atBottomCenterOf(above);
            double reach = canSee(crystalPos) ? range.getValue() : wallsRange.getValue();
            if (mc.player.getEyePosition().distanceTo(crystalPos) > reach) {
                continue;
            }

            // The crystal model needs its whole space free of entities.
            AABB space = new AABB(above.getX(), above.getY(), above.getZ(),
                above.getX() + 1, above.getY() + (oldPlacement.isOn() ? 1 : 2), above.getZ() + 1);
            if (entityBlocks(space)) {
                continue;
            }
            if (!selfSafe(crystalPos)) {
                continue;
            }
            float damage = ExplosionUtil.crystalDamage(target, crystalPos);
            if (damage >= minDamage.getFloat() && damage > bestDamage) {
                bestDamage = damage;
                best = base.immutable();
            }
        }
        return best;
    }

    /** True when a blast at the point stays inside the self damage rules. */
    private boolean selfSafe(Vec3 source) {
        float self = ExplosionUtil.crystalDamage(mc.player, source);
        if (self > maxSelfDamage.getFloat()) {
            return false;
        }
        return !antiSuicide.isOn() || self < ExplosionUtil.totalHealth(mc.player);
    }

    /** True when nothing solid sits between the player's eyes and the point. */
    private boolean canSee(Vec3 point) {
        BlockHitResult hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), point,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() != HitResult.Type.BLOCK;
    }

    /** True if any entity stands in the space a crystal would fill. */
    private boolean entityBlocks(AABB space) {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.isRemoved() || entity.isSpectator()) {
                continue;
            }
            if (entity.getBoundingBox().intersects(space)) {
                return true;
            }
        }
        return false;
    }

    /** The hand holding a crystal. Switches the hotbar over if needed. */
    private InteractionHand crystalHand() {
        if (mc.player.getOffhandItem().is(Items.END_CRYSTAL)) {
            return InteractionHand.OFF_HAND;
        }
        int selected = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getItem(selected).is(Items.END_CRYSTAL)) {
            return InteractionHand.MAIN_HAND;
        }
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.END_CRYSTAL)) {
                if (previousSlot == -1) {
                    previousSlot = selected;
                }
                mc.player.getInventory().setSelectedSlot(i);
                return InteractionHand.MAIN_HAND;
            }
        }
        return null;
    }

    private void restoreSlot() {
        if (previousSlot != -1 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(previousSlot);
        }
        previousSlot = -1;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || planned == null) {
            return;
        }
        event.getBatch().outlineBox(new AABB(planned).deflate(0.002), 0xFFB040FF, false);
        event.getBatch().outlineBox(new AABB(planned.above()).inflate(-0.2, 0, -0.2), 0x80B040FF, false);
    }
}
