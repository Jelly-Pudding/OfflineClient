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

/**
 * A hole with somebody already in it cannot be filled. The server refuses a
 * block inside a player. The point is to seal the holes around an enemy
 * before they reach one.
 */
public final class HoleFiller extends Module {

    // How far ahead of a moving target the search looks.
    private static final double LEAD_TICKS = 8;

    private final NumberSetting range = new NumberSetting("Range",
        "How far you can reach to place.", 4.5, 1, 6, 0.1);
    private final BoolSetting nearEnemies = new BoolSetting("Near enemies only",
        "Only fill holes an enemy could reach. Off fills every hole in range.", true);
    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How close an enemy has to be to a hole for it to count.", 5, 1, 12, 0.5, " blocks")
        .under(nearEnemies);
    private final BoolSetting predict = new BoolSetting("Predict",
        "Fills the holes a moving enemy is heading for first.", true)
        .under(nearEnemies);
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many blocks to place in one round.", 2, 1, 4, 1);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between placing rounds.", 1, 0, 10, 1, " ticks");
    private final BoolSetting genuineOnly = new BoolSetting("Genuine holes",
        "Only fill holes that are blast proof on every side.", true);
    private final BoolSetting ownHole = new BoolSetting("Keep own hole",
        "Never fill the hole you stand in or next to.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward each block.", true);
    private final BoolSetting render = new BoolSetting("Show holes",
        "Outline the holes waiting to be filled.", true);

    private final List<Player> enemies = new ArrayList<>();
    private final List<BlockPos> holes = new ArrayList<>();
    private int timer;
    private final SlotSwap slots = new SlotSwap();

    public HoleFiller() {
        super("HoleFiller", "Seals the holes around an enemy before they can hide in one.", Category.COMBAT);
        addSettings(range, nearEnemies, targetRange, predict, perTick, delay, genuineOnly, ownHole,
            rotate, render);
        searchTags("hole", "obsidian", "crystal", "fill");
    }

    @Override
    public String getSuffix() {
        return count(holes.size());
    }

    @Override
    protected void onEnable() {
        timer = 0;
        slots.forget();
        enemies.clear();
        holes.clear();
    }

    @Override
    protected void onDisable() {
        slots.restore();
        enemies.clear();
        holes.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        holes.clear();
        if (!inGame() || mc.player.isSpectator()) {
            enemies.clear();
            return;
        }
        collectEnemies();
        if (nearEnemies.isOn() && enemies.isEmpty()) {
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
            if (BlockUtil.placeAny(pos, rotate.isOn(), true)) {
                placed++;
            }
        }
        if (placed > 0) {
            timer = delay.getInt();
        }
        slots.restore();
    }

    private void collectEnemies() {
        enemies.clear();
        double reach = range.getValue() + targetRange.getValue();
        for (Player player : mc.level.players()) {
            if (!EntityUtil.isEnemy(player) || player.isCreative()) {
                continue;
            }
            if (mc.player.distanceTo(player) <= reach) {
                enemies.add(player);
            }
        }
    }

    private void collectHoles() {
        BlockPos own = mc.player.blockPosition();
        // positionsWithin hands them back nearest first. Predicted holes are pulled forward.
        List<BlockPos> soon = new ArrayList<>();
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (ownHole.isOn() && own.distManhattan(pos) <= 1) {
                continue;
            }
            if (!open(pos) || !floored(pos)) {
                continue;
            }
            if (!nearEnemies.isOn()) {
                holes.add(pos.immutable());
            } else if (headedFor(pos)) {
                soon.add(pos.immutable());
            } else if (nearAnEnemy(pos)) {
                holes.add(pos.immutable());
            }
        }
        holes.addAll(0, soon);
    }

    // Open and empty. A hole with a player in it is not open.
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

    private boolean nearAnEnemy(BlockPos pos) {
        Vec3 top = Vec3.upFromBottomCenterOf(pos, 1);
        for (Player enemy : enemies) {
            if (enemy.position().distanceTo(top) <= targetRange.getValue()) {
                return true;
            }
        }
        return false;
    }

    // True when a moving enemy will be standing on the spot shortly.
    private boolean headedFor(BlockPos pos) {
        if (!predict.isOn()) {
            return false;
        }
        Vec3 top = Vec3.upFromBottomCenterOf(pos, 1);
        for (Player enemy : enemies) {
            Vec3 pace = EntityUtil.velocityOf(enemy);
            if (pace.horizontalDistanceSqr() < 0.0004) {
                continue;
            }
            Vec3 ahead = enemy.position().add(pace.x * LEAD_TICKS, 0, pace.z * LEAD_TICKS);
            if (ahead.distanceTo(top) < 1.5) {
                return true;
            }
        }
        return false;
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
