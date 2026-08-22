package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.modules.world.Nuker;
import com.jellypudding.offlineclient.modules.world.VeinMiner;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Mines the surround block next to an enemy standing in a hole so a
 * crystal can reach them.
 */
public final class AutoCity extends Module {

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 6, 1, 10, 0.5, " blocks");
    private final NumberSetting breakRange = new NumberSetting("Break range",
        "How far you can reach to mine.", 4.5, 1, 6, 0.1).min(1);
    private final BoolSetting switchTool = new BoolSetting("Switch tool",
        "Swap to your fastest hotbar tool first.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn toward the block on the server side.", true);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off when done",
        "Turn off once the block is gone.", true);
    private final BoolSetting render = new BoolSetting("Show target",
        "Outline the block being mined.", true);

    private BlockPos current;
    private int previousSlot = -1;
    private String targetName;

    public AutoCity() {
        super("AutoCity", "Mines the block guarding an enemy in a hole.", Category.COMBAT);
        addSettings(targetRange, breakRange, switchTool, rotate, toggleOff, render);
        searchTags("city", "surround", "obsidian");
    }

    @Override
    public String getSuffix() {
        return targetName;
    }

    @Override
    protected void onEnable() {
        current = null;
        previousSlot = -1;
        targetName = null;
        // Only one module can drive BlockMiner at a time.
        ModuleManager modules = OfflineClient.INSTANCE.getModuleManager();
        if (modules != null) {
            modules.get(Nuker.class).setEnabled(false);
            modules.get(VeinMiner.class).setEnabled(false);
        }
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        restoreSlot();
        current = null;
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
            stopMining();
            return;
        }

        if (current != null && BlockUtil.state(current).isAir()) {
            restoreSlot();
            current = null;
            if (toggleOff.isOn()) {
                setEnabled(false);
                return;
            }
        }
        if (current != null && BlockUtil.distanceTo(current) > breakRange.getValue()) {
            stopMining();
        }
        if (current == null) {
            current = cityBlock(target);
        }
        if (current == null) {
            return;
        }

        if (switchTool.isOn()) {
            selectBestTool(BlockUtil.state(current));
        }
        if (!BlockMiner.mine(current, rotate.isOn())) {
            stopMining();
        }
    }

    /** The blast proof block beside the target's feet that is closest to the player. */
    private BlockPos cityBlock(Player target) {
        BlockPos feet = target.blockPosition();
        BlockPos best = null;
        double bestDistance = breakRange.getValue();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos pos = feet.relative(side);
            BlockState state = BlockUtil.state(pos);
            if (state.isAir() || state.getBlock().getExplosionResistance() < 600
                || !BlockUtil.isBreakable(pos)) {
                continue;
            }
            double distance = BlockUtil.distanceTo(pos);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    /** Puts the fastest hotbar tool for the block in hand. */
    private void selectBestTool(BlockState state) {
        int bestSlot = -1;
        float bestSpeed = 1;
        for (int i = 0; i < 9; i++) {
            float speed = ItemUtil.miningSpeed(mc.player.getInventory().getItem(i), state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        if (bestSlot == -1) {
            return;
        }
        int selected = mc.player.getInventory().getSelectedSlot();
        if (selected != bestSlot) {
            if (previousSlot == -1) {
                previousSlot = selected;
            }
            mc.player.getInventory().setSelectedSlot(bestSlot);
        }
    }

    private void stopMining() {
        if (current != null) {
            BlockMiner.release();
        }
        restoreSlot();
        current = null;
    }

    private void restoreSlot() {
        if (previousSlot != -1 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(previousSlot);
        }
        previousSlot = -1;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || current == null || BlockUtil.state(current).isAir()) {
            return;
        }
        event.getBatch().outlineBox(new AABB(current).deflate(0.002), 0xFFFF4040, false);
    }
}
