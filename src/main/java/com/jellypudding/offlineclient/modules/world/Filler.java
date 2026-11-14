package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.AreaPlacer;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.CornerPicker;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

// Fills a box marked with two corners. The bottom layer goes in first and each layer has the
// one below it to stand on.
public final class Filler extends AreaPlacer implements CornerPicker.Taker {

    private static final int TARGET_COLOR = 0xFF60D060;

    // How often the whole box is looked over for spots still to fill.
    private static final int COUNT_TICKS = 20;

    private final NumberSetting maxBlocks = CornerPicker.maxBlocks();
    private final BoolSetting replace = new BoolSetting("Replace",
        "Breaks blocks in the box that are not on the list before filling the space.", false);
    private final BoolSetting keepActive = new BoolSetting("Keep active",
        "Stays on after the box is full ready for another.", false);
    private final BoxStyle boxStyle = BoxStyle.white(BoxStyle.Shape.LINES);

    private final CornerPicker corners = new CornerPicker(this, boxStyle, maxBlocks::getInt, "filled");
    private final WorldWatch world = new WorldWatch();
    // Spots of the box still to fill or break at the last count and the ticks to the next one.
    private int left;
    private int countWait;

    public Filler() {
        super("Filler", "Fills the box between two opposite corners you click with the use key.",
            "The blocks the box is filled with. Click to pick them.",
            List.of(Blocks.COBBLESTONE, Blocks.STONE, Blocks.DIRT, Blocks.NETHERRACK, Blocks.OBSIDIAN),
            "Outline the spots waiting to be filled.", 0, TARGET_COLOR);
        addSettings(range, wallsRange, maxBlocks, blocks, replace, perRound, delay, rotate, keepActive, render);
        addSettings(boxStyle.settings());
        searchTags("fill box", "cuboid", "wall", "floor");
    }

    @Override
    public String getSuffix() {
        String waiting = corners.waitingFor();
        return waiting != null ? waiting : left + " left";
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
        corners.open();
        world.accept();
    }

    @Override
    protected void onDisable() {
        super.onDisable();
        clear();
        corners.close();
        world.forget();
    }

    private void clear() {
        corners.clear();
        left = 0;
        countWait = 0;
    }

    @Override
    public void markCorner(BlockPos pos) {
        corners.mark(pos);
        if (!corners.done()) {
            tell("first corner at §f" + BlockUtil.text(pos) + "§7.");
            return;
        }
        // The whole box is looked over every second and a huge one would stall the game.
        if (corners.withinLimit()) {
            countWait = 0;
            tell("second corner at §f" + BlockUtil.text(pos) + "§7. §f" + corners.volume()
                + "§7 blocks in the box.");
        }
    }

    // Runs before the placing round. Blocks in the way come out first.
    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.player.isSpectator()) {
            return;
        }
        if (world.changed()) {
            // Corners taken in another world point at nothing here.
            stopDigging();
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
            stopDigging();
        }
    }

    private void finish() {
        tell("filled that box.");
        stopDigging();
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
        Vec3 eye = mc.player.getEyePosition();
        dig(this::inBreakReach, Comparator.<BlockPos>comparingInt(pos -> -pos.getY())
            .thenComparingDouble(pos -> eye.distanceToSqr(Vec3.atCenterOf(pos))));
    }

    // A block that is not on the list and can be dug from here. The block underfoot waits
    // until you move off it.
    private boolean inBreakReach(BlockPos pos) {
        return corners.contains(pos) && inReach(pos) && inTheWay(pos) && !BlockUtil.isStandingOn(pos);
    }

    private boolean inTheWay(BlockPos pos) {
        return !BlockUtil.isReplaceable(pos) && BlockUtil.diggable(pos) && !allowed(BlockUtil.state(pos).getBlock());
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
        if (corners.done() && digging() != null) {
            event.getBatch().outlineBlock(digging(), CornerPicker.COLOR, false);
        }
    }
}
