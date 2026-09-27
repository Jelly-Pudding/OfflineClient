package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.AreaPlacer;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

// Remembers each block your feet leave and fills it once your body has stepped off its
// column. A jump in place then leaves nothing under you. A spot out of reach is given up.
public final class TrailMaker extends AreaPlacer {

    private static final int TARGET_COLOR = 0xFF70E0A0;

    // Spots still waiting to be filled. The oldest drop off past this.
    private static final int MAX_SPOTS = 64;

    private final NumberSetting height = new NumberSetting("Height",
        "How many blocks tall the trail is. One lays it at your feet and two leaves a wall.",
        1, 1, 3, 1, " blocks").min(1);
    private final BoolSetting onlyOnGround = new BoolSetting("Only on ground",
        "Leaves nothing where you jumped or fell through the air.", true);

    private final Deque<BlockPos> spots = new ArrayDeque<>();
    private final WorldWatch world = new WorldWatch();
    private BlockPos last;
    private boolean stoodOnLast;

    public TrailMaker() {
        super("TrailMaker", "Places a block behind you as you walk.",
            "The blocks the trail is made of. Click to pick them.",
            List.of(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.NETHERRACK, Blocks.DIRT,
                Blocks.STONE, Blocks.DEEPSLATE),
            "Outline the spots waiting to be filled.", 0, TARGET_COLOR);
        addSettings(height, onlyOnGround, blocks, range, wallsRange, perTick, delay, rotate, render);
        searchTags("trail", "wall behind", "block trail");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        forget();
    }

    @Override
    protected void onDisable() {
        super.onDisable();
        forget();
    }

    private void forget() {
        spots.clear();
        last = null;
        stoodOnLast = false;
    }

    @Override
    protected void collect() {
        if (world.changed()) {
            forget();
        }
        track();
        Iterator<BlockPos> it = spots.iterator();
        while (it.hasNext()) {
            BlockPos spot = it.next();
            if (!BlockUtil.isReplaceable(spot) || !withinRange(spot, range.getValue())) {
                it.remove();
            } else if (BlockUtil.blockFits(spot) && offColumn(spot) && inReach(spot)) {
                targets.add(spot);
            }
        }
    }

    // The block the feet just left joins the trail.
    private void track() {
        BlockPos feet = mc.player.blockPosition();
        if (feet.equals(last)) {
            stoodOnLast |= mc.player.onGround();
            return;
        }
        if (last != null && (stoodOnLast || !onlyOnGround.isOn())) {
            for (int i = 0; i < height.getInt(); i++) {
                BlockPos spot = last.above(i);
                if (!spots.contains(spot)) {
                    spots.addLast(spot);
                }
            }
            while (spots.size() > MAX_SPOTS) {
                spots.removeFirst();
            }
        }
        last = feet;
        stoodOnLast = mc.player.onGround();
    }

    private static boolean offColumn(BlockPos spot) {
        AABB body = mc.player.getBoundingBox();
        return !body.intersects(spot.getX(), body.minY, spot.getZ(), spot.getX() + 1, body.maxY, spot.getZ() + 1);
    }

    @Override
    protected boolean placeOn(BlockPos pos) {
        return BlockUtil.placeAny(pos, rotate.isOn(), true);
    }
}
