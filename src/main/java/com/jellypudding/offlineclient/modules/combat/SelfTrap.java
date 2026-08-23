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
import com.jellypudding.offlineclient.util.BlockUtil.TrapMode;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;

public final class SelfTrap extends Module {

    private final EnumSetting<TrapMode> mode = new EnumSetting<>("Mode",
        "Top covers your head and Full seals the sides too.", TrapMode.TOP);
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
        List<BlockPos> missing = BlockUtil.trapSpots(
            mc.player.blockPosition(), mode.is(TrapMode.FULL));
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

        int slot = BlockUtil.findBlastProofSlot();
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

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        for (BlockPos pos : pending) {
            event.getBatch().outlineBlock(pos, 0xFFE0A030, false);
        }
    }
}
