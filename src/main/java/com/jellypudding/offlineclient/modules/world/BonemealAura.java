package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.BlockUseAura;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockTurns;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BreakTime;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TakeFrom;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AzaleaBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealSource;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.BonemealableFeaturePlacerBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

// Feeds bone meal to whatever is still growing around you.
public final class BonemealAura extends BlockUseAura {

    // Ticks before the same moss top is hit again.
    private static final int CLEAR_TICKS = 10;

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
    private final BoolSetting moss = new BoolSetting("Moss",
        "Spreads moss blocks over the stone and dirt round them.", false);
    private final NumberSetting mossLeast = new NumberSetting("Least to turn",
        "How many blocks round a moss block must still be there for it to turn before it is fed.",
        10, 1, BlockTurns.MOSS_COLUMNS, 1).max(BlockTurns.MOSS_COLUMNS).under(moss);
    private final BoolSetting mossClear = new BoolSetting("Clear the top",
        "Breaks the grass or flower or azalea standing on such a moss block. Bone meal only takes on"
            + " moss with air above it.", true).under(moss);
    private final BoolSetting other = new BoolSetting("Other",
        "Everything else bone meal works on such as flowers and mushrooms.", false);

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    // Moss tops hit a moment ago. The server takes a moment to say the plant is gone.
    private final Cooldowns<BlockPos> cleared = new Cooldowns<>();
    private int fed;

    public BonemealAura() {
        super("BonemealAura", "Feeds bone meal to the plants around you.", "Multi meal");
        addSettings(range, multi, lineOfSight, face, swing, fastPlace, whileBreaking, whileRiding,
            takeFrom, saplings, crops, stems, cocoa, seaPickles, moss, mossLeast, mossClear, other);
        searchTags("bone meal", "fertiliser", "grow", "moss");
    }

    @Override
    public String getSuffix() {
        return count(fed, "fed");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        cleared.clear();
        fed = 0;
    }

    @Override
    protected void onDisable() {
        loan.giveBack();
    }

    @Override
    protected boolean busy() {
        return !whileBreaking.isOn() && mc.gameMode.isDestroying()
            || !whileRiding.isOn() && mc.player.isHandsBusy();
    }

    @Override
    protected boolean paced() {
        return !fastPlace.isOn() && mc.rightClickDelay > 0;
    }

    // A sapling often needs several goes and the same one is fed again at once.
    @Override
    protected int retryTicks() {
        return 0;
    }

    @Override
    protected void rest() {
        loan.standAside();
    }

    // A plant only comes off a moss block with bone meal in hand to feed the moss after. Every
    // block at once goes furthest first because a fed plant grows and hides the ones behind it.
    @Override
    protected void work() {
        BlockPos top = moss.isOn() && mossClear.isOn() ? mossTop() : null;
        List<BlockPos> targets = inReach(this::wanted);
        if (targets.isEmpty() && top == null || !loan.hold(Items.BONE_MEAL, takeFrom.getValue().limit())) {
            loan.giveBack();
            return;
        }
        if (top != null) {
            clear(top);
        }
        fed += click(multi.isOn() ? targets.reversed() : targets, pos -> BlockUtil.useOn(pos, false, false));
    }

    private boolean wanted(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        Block block = state.getBlock();
        if (!(block instanceof BonemealableBlock plant)
            || !plant.isValidBonemealTarget(mc.level, pos, state, BonemealSource.INTERACTION)) {
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
        if (block instanceof BonemealableFeaturePlacerBlock) {
            return moss.isOn() && worthMoss(pos, block);
        }
        return other.isOn();
    }

    private boolean worthMoss(BlockPos pos, Block moss) {
        return BlockTurns.mossSpread(mc.level, pos, moss) >= mossLeast.getInt();
    }

    // The plant on the nearest moss block that is worth feeding. Null when there is none.
    private BlockPos mossTop() {
        cleared.tick();
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            Block block = BlockUtil.state(pos).getBlock();
            BlockPos top = pos.above();
            if (block instanceof BonemealableFeaturePlacerBlock && !cleared.contains(top) && grownOnMoss(top)
                && worthMoss(pos, block) && (!lineOfSight.isOn() || BlockUtil.canSee(top))) {
                return top;
            }
        }
        return null;
    }

    // One hit takes the plant off.
    private void clear(BlockPos top) {
        BlockMiner.breakInstantly(top);
        SwingMode.of(swing.isOn()).use();
        cleared.put(top, CLEAR_TICKS);
    }

    // Grass and flowers and azaleas are what a moss patch grows on itself. The carpet it lays
    // as well takes more than one hit.
    private static boolean grownOnMoss(BlockPos top) {
        return BreakTime.clearsInOneHit(top)
            || BlockUtil.state(top).getBlock() instanceof AzaleaBlock && BreakTime.canInstantBreak(top);
    }
}
