package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.TakeFrom;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealSource;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

// Feeds bone meal to whatever is still growing around you.
public final class BonemealAura extends Module {

    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes to reach.", 5, 1, 6, 0.1);
    private final BoolSetting multi = new BoolSetting("Multi meal",
        "Feeds every plant in reach in one tick. Fast but obvious to an anti cheat.", false);
    private final BoolSetting lineOfSight = new BoolSetting("Line of sight",
        "Only feeds plants you can see from where you stand.", true);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the plant on the server side.", true);
    private final BoolSetting fastPlace = new BoolSetting("Fast place",
        "Ignores the right click cooldown between plants.", true);
    private final BoolSetting whileBreaking = new BoolSetting("Use whilst breaking",
        "Keeps going whilst you are mining a block.", false);
    private final BoolSetting whileRiding = new BoolSetting("Use whilst riding",
        "Keeps going whilst your hands are busy with a mount.", false);
    private final EnumSetting<TakeFrom> takeFrom = TakeFrom.setting("bone meal", TakeFrom.HANDS);
    private final BoolSetting saplings = new BoolSetting("Saplings",
        "Grows saplings into trees.", true);
    private final BoolSetting crops = new BoolSetting("Crops",
        "Wheat and carrots and potatoes and beetroot.", true);
    private final BoolSetting stems = new BoolSetting("Stems",
        "Pumpkin and melon stems.", true);
    private final BoolSetting cocoa = new BoolSetting("Cocoa",
        "Cocoa pods on jungle logs.", true);
    private final BoolSetting seaPickles = new BoolSetting("Sea pickles",
        "Spreads sea pickles across coral.", true);
    private final BoolSetting other = new BoolSetting("Other",
        "Everything else bone meal works on such as flowers and mushrooms.", false);

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private int fed;

    public BonemealAura() {
        super("BonemealAura", "Feeds bone meal to the plants around you.", Category.WORLD);
        addSettings(range, multi, lineOfSight, rotate, swing, fastPlace, whileBreaking, whileRiding,
            takeFrom, saplings, crops, stems, cocoa, seaPickles, other);
        searchTags("bone meal", "fertiliser", "grow");
    }

    @Override
    public String getSuffix() {
        return count(fed, "fed");
    }

    @Override
    protected void onEnable() {
        fed = 0;
    }

    @Override
    protected void onDisable() {
        loan.giveBack();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.gameMode == null || mc.player.isSpectator() || mc.gui.screen() != null) {
            return;
        }
        if (mc.player.isDeadOrDying()) {
            // Respawn gives a fresh inventory. A borrowed slot could hold anything.
            loan.forget();
            return;
        }
        if (!fastPlace.isOn() && mc.rightClickDelay > 0) {
            return;
        }
        if (!whileBreaking.isOn() && mc.gameMode.isDestroying()) {
            return;
        }
        if (!whileRiding.isOn() && mc.player.isHandsBusy()) {
            return;
        }
        List<BlockPos> targets = targets();
        if (targets.isEmpty()) {
            loan.giveBack();
            return;
        }
        if (!holdBoneMeal()) {
            loan.giveBack();
            return;
        }
        if (multi.isOn()) {
            // Furthest plants first as a fed plant grows and hides the ones behind it.
            // Only the nearest turns since the rotation manager keeps one angle a tick.
            int before = fed;
            for (int i = targets.size() - 1; i >= 0; i--) {
                if (BlockUtil.useOn(targets.get(i), rotate.isOn() && i == 0, false)) {
                    fed++;
                }
            }
            if (fed > before) {
                swing.getValue().swing(InteractionHand.MAIN_HAND);
            }
            return;
        }
        if (BlockUtil.useOn(targets.getFirst(), rotate.isOn(), false)) {
            swing.getValue().swing(InteractionHand.MAIN_HAND);
            fed++;
            mc.rightClickDelay = InputUtil.USE_DELAY;
        }
    }

    // True once bone meal is in the main hand.
    private boolean holdBoneMeal() {
        if (mc.player.getMainHandItem().is(Items.BONE_MEAL)) {
            return true;
        }
        int limit = takeFrom.getValue().limit();
        int slot = InventoryUtil.findSlot(Items.BONE_MEAL, limit);
        return slot != -1 && loan.select(slot);
    }

    // Nearest first.
    private List<BlockPos> targets() {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (wanted(pos) && (!lineOfSight.isOn() || BlockUtil.canSee(pos))) {
                result.add(pos);
            }
        }
        return result;
    }

    private boolean wanted(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        Block block = state.getBlock();
        if (!(block instanceof BonemealableBlock plant) || !plant.isValidBonemealTarget(mc.level, pos, state, BonemealSource.INTERACTION)) {
            return false;
        }
        // Grass only sprouts more grass and flowers. Never worth the meal.
        if (block instanceof GrassBlock) {
            return false;
        }
        if (block instanceof SaplingBlock) {
            return saplings.isOn();
        }
        if (block instanceof CropBlock) {
            return crops.isOn();
        }
        if (block instanceof StemBlock) {
            return stems.isOn();
        }
        if (block instanceof CocoaBlock) {
            return cocoa.isOn();
        }
        if (block instanceof SeaPickleBlock) {
            return seaPickles.isOn();
        }
        return other.isOn();
    }
}
