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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Covers the player's head with obsidian so crystals cannot be placed on it.
 */
public final class SelfTrap extends Module {

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

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Top covers your head and Full seals the sides too.", Mode.TOP);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between placements.", 1, 0, 5, 1, " ticks");
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward each block.", true);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off when done",
        "Turn off once every spot is filled.", true);
    private final BoolSetting render = new BoolSetting("Show blocks",
        "Outline the spots still to fill.", true);

    private int timer;
    private int previousSlot = -1;

    public SelfTrap() {
        super("SelfTrap", "Places obsidian above your head to stop crystals.", Category.COMBAT);
        addSettings(mode, delay, rotate, toggleOff, render);
        searchTags("obsidian", "head", "crystal");
    }

    @Override
    protected void onEnable() {
        timer = 0;
        previousSlot = -1;
    }

    @Override
    protected void onDisable() {
        restoreSlot();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        List<BlockPos> missing = missingSpots();
        if (missing.isEmpty()) {
            restoreSlot();
            if (toggleOff.isOn()) {
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

        BlockPos target = missing.getFirst();
        Direction support = BlockUtil.findPlaceSupport(target);
        boolean placed = support != null
            ? BlockUtil.place(target, support, rotate.isOn(), true)
            : BlockUtil.placeDirect(target, rotate.isOn(), true);
        if (placed) {
            timer = delay.getInt();
        }
        restoreSlot();
    }

    /** The trap spots that are still open. */
    private List<BlockPos> missingSpots() {
        List<BlockPos> result = new ArrayList<>();
        BlockPos feet = mc.player.blockPosition();
        addOpen(result, feet.above(2));
        if (mode.is(Mode.FULL)) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                addOpen(result, feet.above().relative(side));
            }
        }
        return result;
    }

    private void addOpen(List<BlockPos> result, BlockPos pos) {
        if (!BlockUtil.isReplaceable(pos)) {
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
        for (BlockPos pos : missingSpots()) {
            event.getBatch().outlineBox(new AABB(pos).deflate(0.002), 0xFFE0A030, false);
        }
    }
}
