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
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

public final class AntiAnchor extends Module {


    private final NumberSetting range = new NumberSetting("Range",
        "How far around you to watch for anchors.", 4.5, 1, 6, 0.1).min(1);
    private final BoolSetting onlyDangerous = new BoolSetting("Only dangerous",
        "Ignore anchors too far away to hurt you.", true);
    private final BoolSetting switchTool = new BoolSetting("Switch tool",
        "Swap to your fastest hotbar tool first.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn toward the anchor on the server side.", true);
    private final BoolSetting render = new BoolSetting("Show anchor",
        "Outline the anchor being broken.", true);

    private BlockPos current;
    private final SlotSwap slots = new SlotSwap();

    public AntiAnchor() {
        super("AntiAnchor", "Breaks an enemy respawn anchor placed next to you.", Category.COMBAT);
        addSettings(range, onlyDangerous, switchTool, rotate, render);
        searchTags("respawn anchor", "anchor aura", "defence");
    }

    @Override
    public String getSuffix() {
        return current == null ? null : "breaking";
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        current = null;
        slots.forget();
    }

    @Override
    protected void onDisable() {
        stop();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        boolean setsSpawn = mc.level.environmentAttributes()
            .getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, mc.player.blockPosition());
        if (setsSpawn) {
            stop();
            return;
        }

        if (current != null && !isThreat(current)) {
            stop();
        }
        if (current == null) {
            current = nearestAnchor();
        }
        if (current == null) {
            return;
        }
        if (switchTool.isOn()) {
            ItemUtil.selectBestTool(BlockUtil.state(current), slots);
        }
        if (!BlockMiner.mine(current, rotate.isOn())) {
            stop();
        }
    }

    private BlockPos nearestAnchor() {
        BlockPos best = null;
        double bestDistance = range.getValue();
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (!isThreat(pos)) {
                continue;
            }
            double distance = BlockUtil.distanceTo(pos);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    private boolean isThreat(BlockPos pos) {
        return BlockUtil.state(pos).getBlock() == Blocks.RESPAWN_ANCHOR
            && ExplosionUtil.respawnBlockThreat(pos, range.getValue(), onlyDangerous.isOn());
    }

    private void stop() {
        if (current != null) {
            BlockMiner.release();
        }
        slots.restore();
        current = null;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (render.isOn() && current != null) {
            event.getBatch().outlineBox(new AABB(current).deflate(0.002), 0xFFFF4040, false);
        }
    }
}
