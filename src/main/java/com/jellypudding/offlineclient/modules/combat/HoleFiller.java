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
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;

public final class HoleFiller extends Module {

    // How far ahead of a moving target the search looks.
    private static final double LEAD_TICKS = 8;

    // How close a target has to be to a hole for it to count as theirs.
    private static final double FEET_RANGE = 1.6;

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 7, 1, 12, 0.5, " blocks");
    private final NumberSetting range = new NumberSetting("Range",
        "How far you can reach to place.", 4.5, 1, 6, 0.1);
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many blocks to place in one round.", 2, 1, 4, 1);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between placing rounds.", 1, 0, 10, 1, " ticks");
    private final BoolSetting genuineOnly = new BoolSetting("Genuine holes",
        "Only fill holes that are blast proof on every side.", true);
    private final BoolSetting predict = new BoolSetting("Predict",
        "Also fill the holes a moving target is heading for.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward each block.", true);
    private final BoolSetting render = new BoolSetting("Show holes",
        "Outline the holes waiting to be filled.", true);

    private final List<Player> targets = new ArrayList<>();
    private final List<BlockPos> holes = new ArrayList<>();
    private int timer;
    private final SlotSwap slots = new SlotSwap();

    public HoleFiller() {
        super("HoleFiller", "Fills the holes an enemy hides in with obsidian.", Category.COMBAT);
        addSettings(targetRange, range, perTick, delay, genuineOnly, predict, rotate, render);
        searchTags("hole", "obsidian", "crystal", "fill");
    }

    @Override
    public String getSuffix() {
        return holes.isEmpty() ? null : String.valueOf(holes.size());
    }

    @Override
    protected void onEnable() {
        timer = 0;
        slots.forget();
        targets.clear();
        holes.clear();
    }

    @Override
    protected void onDisable() {
        slots.restore();
        targets.clear();
        holes.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        holes.clear();
        if (!inGame() || mc.player.isSpectator()) {
            targets.clear();
            return;
        }
        collectTargets();
        if (targets.isEmpty()) {
            slots.restore();
            return;
        }
        collectHoles();
        if (holes.isEmpty()) {
            slots.restore();
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        int slot = BlockUtil.findBlastProofSlot();
        if (slot == -1) {
            slots.restore();
            return;
        }
        slots.select(slot);

        int placed = 0;
        for (BlockPos pos : holes) {
            if (placed >= perTick.getInt()) {
                break;
            }
            Direction support = BlockUtil.findPlaceSupport(pos);
            if (support != null) {
                BlockUtil.place(pos, support, rotate.isOn(), true);
            } else {
                BlockUtil.placeDirect(pos, rotate.isOn(), true);
            }
            // The packet leaves whatever the local result is.
            placed++;
        }
        if (placed > 0) {
            timer = delay.getInt();
        }
        slots.restore();
    }

    private void collectTargets() {
        targets.clear();
        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator() || player.isCreative()) {
                continue;
            }
            if (EntityUtil.isFriend(player)) {
                continue;
            }
            if (mc.player.distanceTo(player) <= targetRange.getValue()) {
                targets.add(player);
            }
        }
    }

    private void collectHoles() {
        // positionsWithin already hands them back nearest first.
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (!open(pos) || !floored(pos) || !claimed(pos)) {
                continue;
            }
            holes.add(pos.immutable());
        }
    }

    private boolean open(BlockPos pos) {
        if (!BlockUtil.isReplaceable(pos) || !BlockUtil.state(pos.above()).isAir()) {
            return false;
        }
        return mc.level.isUnobstructed(Blocks.OBSIDIAN.defaultBlockState(), pos, CollisionContext.empty());
    }

    // True when the spot is really a hole.
    private boolean floored(BlockPos pos) {
        if (!genuineOnly.isOn()) {
            return BlockUtil.isSolid(pos.below());
        }
        for (Direction side : Direction.values()) {
            if (side == Direction.UP) {
                continue;
            }
            BlockPos wall = pos.relative(side);
            if (!BlockUtil.isSolid(wall)
                || BlockUtil.state(wall).getBlock().getExplosionResistance() < BlockUtil.BLAST_PROOF) {
                return false;
            }
        }
        return true;
    }

    // True when a target stands in the hole or is moving onto it.
    private boolean claimed(BlockPos pos) {
        Vec3 top = Vec3.upFromBottomCenterOf(pos, 1);
        for (Player target : targets) {
            if (target.getY() <= pos.getY() - 1) {
                continue;
            }
            if (target.position().distanceTo(top) < FEET_RANGE) {
                return true;
            }
            if (predict.isOn() && ahead(target).distanceTo(top) < FEET_RANGE) {
                return true;
            }
        }
        return false;
    }

    private Vec3 ahead(Player target) {
        return target.position().add(EntityUtil.velocityOf(target).scale(LEAD_TICKS));
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        int next = perTick.getInt();
        for (int i = 0; i < holes.size(); i++) {
            int color = i < next ? 0xFFC080FF : 0x80C080FF;
            event.getBatch().outlineBlock(holes.get(i), color, false);
        }
    }
}
