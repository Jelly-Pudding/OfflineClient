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
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    private final SlotSwap slots = new SlotSwap();
    private String targetName;
    private boolean placed;

    // The spots the last tick found. The unobstructed test walks the entity list.
    private List<BlockPos> pending = List.of();

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
        slots.forget();
        targetName = null;
        placed = false;
        pending = List.of();
    }

    @Override
    protected void onDisable() {
        slots.restore();
        targetName = null;
        pending = List.of();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        pending = List.of();
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        Player target = EntityUtil.nearestEnemy(targetRange.getValue());
        targetName = target == null ? null : target.getGameProfile().name();
        if (target == null) {
            slots.restore();
            return;
        }

        List<BlockPos> missing = missingSpots(target);
        pending = missing;
        if (missing.isEmpty()) {
            slots.restore();
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
            slots.restore();
            return;
        }
        slots.select(slot);

        int done = 0;
        boolean rotated = false;
        for (BlockPos pos : missing) {
            if (done >= perTick.getInt()) {
                break;
            }
            Direction support = BlockUtil.findPlaceSupport(pos);
            // Several look packets in one tick look obviously wrong to the server.
            boolean turn = rotate.isOn() && !rotated;
            rotated |= turn;
            boolean ok = support != null
                ? BlockUtil.place(pos, support, turn, true)
                : BlockUtil.placeDirect(pos, turn, true);
            if (ok) {
                done++;
                placed = true;
            }
        }
        if (done > 0) {
            timer = delay.getInt();
        }
        slots.restore();
    }

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

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        for (BlockPos pos : pending) {
            event.getBatch().outlineBox(new AABB(pos).deflate(0.002), 0xFFB040FF, false);
        }
    }
}
