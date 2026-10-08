package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.AreaPlacer;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.CornerPicker;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

// Fills a box marked with two corners. The bottom layer goes in first and each layer has the
// one below it to stand on.
public final class Filler extends AreaPlacer {

    private static final int TARGET_COLOR = 0xFF60D060;

    // How often the whole box is looked over for spots still to fill.
    private static final int COUNT_TICKS = 20;

    private final NumberSetting maxBlocks = new NumberSetting("Max blocks",
        "The most blocks one box may hold.", 4096, 64, 16384, 64).min(1);
    private final BoolSetting replace = new BoolSetting("Replace",
        "Breaks blocks in the box that are not on the list before filling the space.", false);
    private final BoolSetting keepActive = new BoolSetting("Keep active",
        "Stays on after the box is full ready for another.", false);
    private final BoxStyle boxStyle = BoxStyle.white(BoxStyle.Shape.LINES);

    private final CornerPicker corners = new CornerPicker();
    private final WorldWatch world = new WorldWatch();
    // The block the Replace switch is breaking. Null whilst none is.
    private BlockPos breaking;
    // Spots of the box still to fill or break at the last count and the ticks to the next one.
    private int left;
    private int countWait;

    public Filler() {
        super("Filler", "Fills a box between two corners you mark with the bind.",
            "The blocks the box is filled with. Click to pick them.",
            List.of(Blocks.COBBLESTONE, Blocks.STONE, Blocks.DIRT, Blocks.NETHERRACK, Blocks.OBSIDIAN),
            "Outline the spots waiting to be filled.", 0, TARGET_COLOR);
        addSettings(range, wallsRange, maxBlocks, blocks, replace, perRound, delay, rotate, keepActive, render);
        addSettings(boxStyle.settings());
        searchTags("fill box", "cuboid", "wall", "floor");
    }

    @Override
    public String getSuffix() {
        String prompt = corners.prompt();
        return prompt != null ? prompt : left + " left";
    }

    // Bringing this back at launch would start filling at once.
    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        clear();
        world.accept();
    }

    @Override
    protected void onDisable() {
        super.onDisable();
        stopBreaking();
        clear();
        world.forget();
    }

    private void clear() {
        corners.clear();
        left = 0;
        countWait = 0;
    }

    // The bind marks the block under the crosshair whilst the module is on. Pressing it with
    // nothing in view turns the module off again.
    @Override
    public void onKeybind() {
        BlockHitResult hit = isEnabled() && inGame() ? BlockUtil.aimedBlock() : null;
        if (hit == null) {
            toggle();
            return;
        }
        BlockPos pos = hit.getBlockPos().immutable();
        if (!corners.started() || corners.done()) {
            stopBreaking();
            clear();
            corners.mark(pos);
            ChatUtil.message("§bFiller §7first corner at §f" + BlockUtil.text(pos) + "§7.");
            return;
        }
        corners.mark(pos);
        // The whole box is looked over every second and a huge one would stall the game.
        if (!corners.withinLimit(maxBlocks.getInt())) {
            return;
        }
        countWait = 0;
        ChatUtil.message("§bFiller §7second corner at §f" + BlockUtil.text(pos) + "§7. §f" + corners.volume()
            + "§7 blocks in the box.");
    }

    // Runs before the placing round. Blocks in the way come out first.
    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (world.changed()) {
            // Corners taken in another world point at nothing here.
            stopBreaking();
            clear();
            return;
        }
        if (!corners.done()) {
            return;
        }
        if (--countWait <= 0) {
            countWait = COUNT_TICKS;
            left = countLeft();
            if (left == 0) {
                finish();
                return;
            }
        }
        if (replace.isOn()) {
            breakNext();
        } else {
            stopBreaking();
        }
    }

    private void finish() {
        ChatUtil.message("§bFiller §7filled that box.");
        stopBreaking();
        clear();
        if (!keepActive.isOn()) {
            setEnabled(false);
        }
    }

    @Override
    protected void collect() {
        if (!corners.done()) {
            return;
        }
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (corners.contains(pos) && BlockUtil.blockFits(pos) && inReach(pos)) {
                targets.add(pos.immutable());
            }
        }
        Vec3 eye = mc.player.getEyePosition();
        targets.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
            .thenComparingDouble(pos -> eye.distanceToSqr(Vec3.atCenterOf(pos))));
    }

    @Override
    protected boolean placeOn(BlockPos pos) {
        return BlockUtil.placeAny(pos, rotate.isOn(), true);
    }

    // The top layers come out first. Sand and gravel never drop onto a cleared spot.
    private void breakNext() {
        // Letting go at once keeps a block it is done with from passing for another module's block.
        if (breaking != null && !inBreakReach(breaking)) {
            stopBreaking();
        }
        if (breaking == null) {
            Vec3 eye = mc.player.getEyePosition();
            breaking = BlockUtil.positionsWithin(range.getValue()).stream()
                .filter(this::inBreakReach)
                .map(BlockPos::immutable)
                .min(Comparator.<BlockPos>comparingInt(pos -> -pos.getY())
                    .thenComparingDouble(pos -> eye.distanceToSqr(Vec3.atCenterOf(pos))))
                .orElse(null);
        }
        if (breaking != null && !BlockMiner.minesOther(breaking) && !BlockMiner.mine(breaking, rotate.isOn())) {
            breaking = null;
        }
    }

    // A block that is not on the list and can be dug from here. The block underfoot waits
    // until you move off it.
    private boolean inBreakReach(BlockPos pos) {
        return corners.contains(pos) && inReach(pos) && inTheWay(pos) && !BlockUtil.isStandingOn(pos);
    }

    private boolean inTheWay(BlockPos pos) {
        return !BlockUtil.isReplaceable(pos) && BlockUtil.diggable(pos) && !allowed(BlockUtil.state(pos).getBlock());
    }

    private void stopBreaking() {
        if (breaking != null) {
            BlockMiner.release(breaking);
            breaking = null;
        }
    }

    // Spots of the whole box that still want a block. Replace adds the blocks in the way.
    private int countLeft() {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(corners.first(), corners.second())) {
            if (BlockUtil.isReplaceable(pos) || replace.isOn() && inTheWay(pos)) {
                count++;
            }
        }
        return count;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        corners.draw(event.getBatch(), boxStyle);
        if (corners.done() && breaking != null) {
            event.getBatch().outlineBlock(breaking, CornerPicker.COLOR, false);
        }
    }
}
