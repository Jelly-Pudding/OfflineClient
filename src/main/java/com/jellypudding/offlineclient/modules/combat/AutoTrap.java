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
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AutoTrap extends Module {

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 4, 1, 10, 0.5, " blocks");
    private final NumberSetting placeRange = new NumberSetting("Place range",
        "How far you can reach to place.", 4.5, 1, 6, 0.1);
    private final EnumSetting<TrapMode> mode = new EnumSetting<>("Mode",
        "Which blocks go round the target.", TrapMode.FULL)
        .describe(TrapMode.TOP, "Covers their head only.")
        .describe(TrapMode.FULL, "Seals their head and the sides at head height.");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between placing rounds.", 1, 0, 5, 1, " ticks");
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many blocks to place in one round.", 2, 1, 4, 1);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards each block.", true);
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
        targetName = EntityUtil.nameOf(target);
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

        int slot = BlockUtil.findBlastProofSlot();
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
            boolean turn = rotate.isOn() && !rotated;
            rotated |= turn;
            boolean ok = BlockUtil.placeAny(pos, turn, true);
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

    // Furthest first. The near side is left open for as long as possible.
    private List<BlockPos> missingSpots(Player target) {
        List<BlockPos> result = new ArrayList<>(
            BlockUtil.trapSpots(target.blockPosition(), mode.is(TrapMode.FULL)));
        result.removeIf(pos -> BlockUtil.distanceTo(pos) > placeRange.getValue());
        result.sort(Comparator.comparingDouble(BlockUtil::distanceTo).reversed());
        return result;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        for (BlockPos pos : pending) {
            event.getBatch().outlineBlock(pos, 0xFFB040FF, false);
        }
    }
}
