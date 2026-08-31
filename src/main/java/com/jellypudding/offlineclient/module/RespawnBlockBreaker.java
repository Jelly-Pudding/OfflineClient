package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.BlockPos;

/**
 * The shape shared by the modules that break a respawn block placed beside
 * the player. A subclass says what counts as a threat and whether the world
 * lets that block go off at all.
 */
public abstract class RespawnBlockBreaker extends Module {

    private static final int TARGET_COLOR = 0xFFFF4040;

    protected final NumberSetting range;
    protected final BoolSetting onlyDangerous;
    private final BoolSetting switchTool;
    protected final BoolSetting rotate;
    protected final BoolSetting render;

    private final SlotSwap slots = new SlotSwap();
    private BlockPos current;

    // addSettings is final and files the settings away without handing out the module.
    @SuppressWarnings("this-escape")
    protected RespawnBlockBreaker(String name, String description, String noun) {
        super(name, description, Category.COMBAT);
        range = new NumberSetting("Range",
            "How far around you to watch for " + noun + ".", 4.5, 1, 6, 0.1).min(1);
        onlyDangerous = new BoolSetting("Only dangerous",
            "Ignore " + noun + " too far away to hurt you.", true);
        switchTool = new BoolSetting("Switch tool",
            "Swap to your fastest hotbar tool first.", true);
        rotate = new BoolSetting("Rotate",
            "Turn towards the block on the server side.", true);
        render = new BoolSetting("Show target",
            "Outline the block being broken.", true);
        addSettings(range, onlyDangerous, switchTool, rotate, render);
    }

    // True when a blast here would reach the player. Distance is only weighed when asked.
    protected boolean dangerous(BlockPos pos) {
        return ExplosionUtil.respawnBlockThreat(pos, range.getValue(), onlyDangerous.isOn());
    }

    // True when the block standing here would go off next to the player.
    protected abstract boolean isThreat(BlockPos pos);

    // False where the world turns the blast into an ordinary use.
    protected abstract boolean explodesHere();

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
        if (!explodesHere()) {
            stop();
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
        if (switchTool.isOn()) {
            ItemUtil.selectBestTool(BlockUtil.state(current), slots);
        }
        if (!BlockMiner.mine(current, rotate.isOn())) {
            stop();
        }
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
            event.getBatch().outlineBlock(current, TARGET_COLOR, false);
        }
    }
}
