package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

// Strips the blast proof cover from an enemy standing in a hole.
public final class AutoCity extends Module {

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 6, 1, 10, 0.5, " blocks");
    private final NumberSetting breakRange = new NumberSetting("Break range",
        "How far you can reach to mine.", 4.5, 1, 6, 0.1);
    private final BoolSetting switchTool = new BoolSetting("Switch tool",
        "Swap to your fastest hotbar tool first.", true);
    private final BoolSetting support = new BoolSetting("Support",
        "Fill the empty block under the city block to give a crystal a base.", true);
    private final BoolSetting chatInfo = new BoolSetting("Chat info",
        "Say why the module stopped.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn toward the block on the server side.", true);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off when done",
        "Turn off once the block is gone.", true);
    private final BoolSetting render = new BoolSetting("Show target",
        "Outline the block being mined.", true);

    private BlockPos current;
    private final SlotSwap slots = new SlotSwap();
    private String targetName;

    public AutoCity() {
        super("AutoCity", "Mines the block guarding an enemy in a hole.", Category.COMBAT);
        addSettings(targetRange, breakRange, support, chatInfo, switchTool, rotate,
            toggleOff, render);
        searchTags("city", "surround", "obsidian");
    }

    @Override
    public String getSuffix() {
        return targetName;
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        current = null;
        slots.forget();
        targetName = null;
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        slots.restore();
        current = null;
        targetName = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }

        Player target = EntityUtil.nearestEnemy(targetRange.getValue());
        targetName = EntityUtil.nameOf(target);
        if (target == null) {
            stopMining();
            return;
        }

        if (current != null && BlockUtil.state(current).isAir()) {
            slots.restore();
            current = null;
            if (toggleOff.isOn()) {
                if (chatInfo.isOn()) {
                    ChatUtil.message("§bAutoCity §7took the block down.");
                }
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
        // A crystal needs a base under the gap the city leaves behind.
        if (support.isOn() && placeSupport()) {
            return;
        }

        if (switchTool.isOn()) {
            ItemUtil.selectBestTool(BlockUtil.state(current), slots);
        }
        if (!BlockMiner.mine(current, rotate.isOn())) {
            stopMining();
        }
    }

    /**
     * Fills the hole under the block about to fall. True when a block went down
     * this tick and mining should wait.
     */
    private boolean placeSupport() {
        BlockPos below = current.below();
        if (!BlockUtil.isReplaceable(below) || BlockUtil.intersectsPlayer(below)) {
            return false;
        }
        if (BlockUtil.distanceTo(below) > breakRange.getValue()) {
            return false;
        }
        int slot = BlockUtil.findBlockSlot();
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        Direction side = BlockUtil.findPlaceSupport(below);
        boolean placed = side != null
            ? BlockUtil.place(below, side, rotate.isOn(), true)
            : BlockUtil.placeDirect(below, rotate.isOn(), true);
        slots.restore();
        return placed;
    }

    private BlockPos cityBlock(Player target) {
        return cityBlock(target, breakRange.getValue());
    }

    // The blast proof block beside the target's feet that is closest to the player.
    public static BlockPos cityBlock(Player target, double reach) {
        BlockPos feet = target.blockPosition();
        BlockPos best = null;
        double bestDistance = reach;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos pos = feet.relative(side);
            BlockState state = BlockUtil.state(pos);
            if (state.isAir() || !BlockUtil.isBreakable(pos)
                || state.getBlock().getExplosionResistance() < BlockUtil.BLAST_PROOF) {
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

    private void stopMining() {
        if (current != null) {
            BlockMiner.release();
        }
        slots.restore();
        current = null;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || current == null || BlockUtil.state(current).isAir()) {
            return;
        }
        event.getBatch().outlineBlock(current, 0xFFFF4040, false);
    }
}
