package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Boxes an enemy's head in with obsidian so they cannot move or be
 * crystalled out easily.
 */
public final class AutoTrap extends Module {

    public enum Mode {
        TOP("Top"),
        FULL("Full");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 4, 1, 10, 0.5, " blocks");
    private final NumberSetting placeRange = new NumberSetting("Place range",
        "How far you can reach to place.", 4.5, 1, 6, 0.1).min(1);
    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Top covers their head and Full seals the sides too.", Mode.FULL);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between placing rounds.", 1, 0, 5, 1, " ticks");
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many blocks to place in one round.", 2, 1, 4, 1).min(1);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward each block.", true);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off when done",
        "Turn off once the target is boxed in.", false);
    private final BoolSetting render = new BoolSetting("Show blocks",
        "Outline the spots still to fill.", true);

    private int timer;
    private int previousSlot = -1;
    private String targetName;
    private boolean placed;

    public AutoTrap() {
        super("AutoTrap", "Places obsidian around an enemy's head to trap them.", Category.COMBAT);
        addSettings(targetRange, placeRange, mode, delay, perTick, rotate, toggleOff, render);
        searchTags("obsidian", "trap", "box");
    }

    @Override
    public String getSuffix() {
        return targetName;
    }

    @Override
    protected void onEnable() {
        timer = 0;
        previousSlot = -1;
        targetName = null;
        placed = false;
    }

    @Override
    protected void onDisable() {
        restoreSlot();
        targetName = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        Player target = EntityUtil.nearestEnemy(targetRange.getValue());
        targetName = target == null ? null : target.getGameProfile().name();
        if (target == null) {
            restoreSlot();
            return;
        }

        List<BlockPos> missing = missingSpots(target);
        if (missing.isEmpty()) {
            restoreSlot();
            if (toggleOff.isOn() && placed) {
                setEnabled(false);
            }
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        int slot = BlockUtil.findBlockSlot(block ->
            block.getExplosionResistance() >= 600 && block.defaultDestroyTime() >= 0);
        if (slot == -1) {
            restoreSlot();
            return;
        }
        selectSlot(slot);

        int done = 0;
        for (BlockPos pos : missing) {
            if (done >= perTick.getInt()) {
                break;
            }
            Direction support = BlockUtil.findPlaceSupport(pos);
            boolean ok = support != null
                ? BlockUtil.place(pos, support, rotate.isOn(), true)
                : BlockUtil.placeDirect(pos, rotate.isOn(), true);
            if (ok) {
                done++;
                placed = true;
            }
        }
        if (done > 0) {
            timer = delay.getInt();
        }
        restoreSlot();
    }

    /** Open trap spots around the target. Farthest first. */
    private List<BlockPos> missingSpots(Player target) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos feet = target.blockPosition();
        addOpen(result, feet.above(2));
        if (mode.is(Mode.FULL)) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                addOpen(result, feet.above().relative(side));
            }
        }
        result.sort(Comparator.comparingDouble(BlockUtil::distanceTo).reversed());
        return result;
    }

    private void addOpen(List<BlockPos> result, BlockPos pos) {
        if (!BlockUtil.isReplaceable(pos) || BlockUtil.distanceTo(pos) > placeRange.getValue()) {
            return;
        }
        if (mc.level.isUnobstructed(Blocks.OBSIDIAN.defaultBlockState(), pos, CollisionContext.empty())) {
            result.add(pos);
        }
    }

    private void selectSlot(int slot) {
        int selected = mc.player.getInventory().getSelectedSlot();
        if (selected == slot) {
            return;
        }
        if (previousSlot == -1) {
            previousSlot = selected;
        }
        mc.player.getInventory().setSelectedSlot(slot);
    }

    private void restoreSlot() {
        if (previousSlot != -1 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(previousSlot);
        }
        previousSlot = -1;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || !inGame()) {
            return;
        }
        Player target = EntityUtil.nearestEnemy(targetRange.getValue());
        if (target == null) {
            return;
        }
        for (BlockPos pos : missingSpots(target)) {
            event.getBatch().outlineBox(new AABB(pos).deflate(0.002), 0xFFB040FF, false);
        }
    }
}
