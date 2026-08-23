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
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;

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
    private final SlotSwap slots = new SlotSwap();
    private boolean placed;

    // The spots the last tick found. The unobstructed test walks the entity list.
    private List<BlockPos> pending = List.of();

    public SelfTrap() {
        super("SelfTrap", "Places obsidian above your head to stop crystals.", Category.COMBAT);
        addSettings(mode, delay, rotate, toggleOff, render);
        searchTags("obsidian", "head", "crystal");
    }

    @Override
    protected void onEnable() {
        timer = 0;
        slots.forget();
        placed = false;
        pending = List.of();
    }

    @Override
    protected void onDisable() {
        slots.restore();
        pending = List.of();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        pending = List.of();
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        List<BlockPos> missing = missingSpots();
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

        BlockPos target = missing.getFirst();
        Direction support = BlockUtil.findPlaceSupport(target);
        boolean ok = support != null
            ? BlockUtil.place(target, support, rotate.isOn(), true)
            : BlockUtil.placeDirect(target, rotate.isOn(), true);
        if (ok) {
            placed = true;
            timer = delay.getInt();
        }
        slots.restore();
    }

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

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        for (BlockPos pos : pending) {
            event.getBatch().outlineBox(new AABB(pos).deflate(0.002), 0xFFE0A030, false);
        }
    }
}
