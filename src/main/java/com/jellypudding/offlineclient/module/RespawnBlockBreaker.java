package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.BlockPos;

public abstract class RespawnBlockBreaker extends Module {

    private static final int TARGET_COLOR = 0xFFFF4040;

    // Two blocks mined at once restart each other and the other breaker waits its turn whilst this
    // is set.
    private static RespawnBlockBreaker breaking;

    protected final NumberSetting range;
    protected final BoolSetting onlyDangerous;
    private final BoolSetting autoTool;
    protected final BoolSetting rotate;
    protected final BoolSetting render;

    private final SlotSwap slots = new SlotSwap();
    private BlockPos current;

    // The escape is safe because addSettings is final and never hands the module out.
    @SuppressWarnings("this-escape")
    protected RespawnBlockBreaker(String name, String description, String noun) {
        super(name, description, Category.COMBAT);
        range = new NumberSetting("Range",
            "How far around you to watch for " + noun + ".", 4.5, 1, 6, 0.1, " blocks");
        onlyDangerous = new BoolSetting("Only dangerous",
            "Ignores " + noun + " too far away to hurt you.", true);
        autoTool = new BoolSetting("Auto tool",
            "Holds the tool AutoTool would pick or else your fastest one.", true);
        rotate = new BoolSetting("Rotate",
            "Turns towards the block on the server side.", true);
        render = new BoolSetting("Show target",
            "Outlines the block being broken.", true);
        addSettings(range, onlyDangerous, autoTool, rotate, render);
    }

    protected boolean dangerous(BlockPos pos) {
        return ExplosionUtil.respawnBlockThreat(pos, range.getValue(), onlyDangerous.isOn());
    }

    protected abstract boolean isThreat(BlockPos pos);

    // False where the world turns the blast into an ordinary use.
    protected abstract boolean explodesHere();

    @Override
    public String getSuffix() {
        return current == null ? null : "breaking";
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
        if (Hands.tied()) {
            return;
        }
        if (!explodesHere()) {
            stop();
            return;
        }
        if (breaking != null && breaking != this) {
            return;
        }
        if (current != null && !isThreat(current)) {
            stop();
        }
        if (current == null) {
            current = BlockUtil.nearestWithin(range.getValue(), this::isThreat);
        }
        if (current == null) {
            return;
        }
        if (autoTool.isOn()) {
            ItemUtil.selectBestTool(BlockUtil.state(current), slots);
        }
        BlockMiner.first(current);
        if (BlockMiner.mine(current, rotate.isOn())) {
            breaking = this;
        } else {
            stop();
        }
    }

    private void stop() {
        if (current != null) {
            BlockMiner.release();
        }
        if (breaking == this) {
            breaking = null;
        }
        slots.restore();
        current = null;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (render.isOn() && current != null) {
            event.getBatch().outlineBlock(current, TARGET_COLOR, false);
        }
    }
}
