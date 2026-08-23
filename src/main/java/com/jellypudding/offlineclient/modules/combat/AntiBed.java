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
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.AABB;

public final class AntiBed extends Module {


    private final NumberSetting range = new NumberSetting("Range",
        "How far around you to watch for beds.", 4.5, 1, 6, 0.1).min(1);
    private final BoolSetting onlyDangerous = new BoolSetting("Only dangerous",
        "Ignore beds too far away to hurt you.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn toward the bed on the server side.", true);
    private final BoolSetting render = new BoolSetting("Show bed",
        "Outline the bed being broken.", true);

    private BlockPos current;

    public AntiBed() {
        super("AntiBed", "Breaks an enemy bed placed next to you.", Category.COMBAT);
        addSettings(range, onlyDangerous, rotate, render);
        searchTags("bed bomb", "bed aura", "defence");
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
        BedRule rule = mc.level.environmentAttributes()
            .getValue(EnvironmentAttributes.BED_RULE, mc.player.blockPosition());
        if (!rule.explodes()) {
            stop();
            return;
        }

        if (current != null && !isThreat(current)) {
            stop();
        }
        if (current == null) {
            current = nearestBed();
        }
        if (current == null) {
            return;
        }
        if (!BlockMiner.mine(current, rotate.isOn())) {
            stop();
        }
    }

    private BlockPos nearestBed() {
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
        return BlockUtil.state(pos).getBlock() instanceof BedBlock
            && ExplosionUtil.respawnBlockThreat(pos, range.getValue(), onlyDangerous.isOn());
    }

    private void stop() {
        if (current != null) {
            BlockMiner.release();
        }
        current = null;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (render.isOn() && current != null) {
            event.getBatch().outlineBox(new AABB(current).deflate(0.002), 0xFFFF4040, false);
        }
    }
}
