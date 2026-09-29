package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.AreaPlacer;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

// Remembers each block your feet leave and fills it once your body has stepped off its
// column. A jump in place then leaves nothing under you. A spot out of reach is given up.
public final class TrailMaker extends AreaPlacer {

    public enum Shape { ROW, PATCH }

    private static final int TARGET_COLOR = 0xFF70E0A0;

    // Spots still waiting to be filled. The oldest drop off past this. Spots out of range
    // go long before.
    private static final int MAX_SPOTS = 1024;

    private final EnumSetting<Shape> shape = new EnumSetting<>("Shape",
        "How the trail spreads round the blocks you walk on.", Shape.ROW)
        .describe(Shape.ROW, "A row across the way you walk.")
        .describe(Shape.PATCH, "A round patch centred on each block you walk on.");
    private final NumberSetting width = new NumberSetting("Width",
        "How many blocks wide the row is. An even width leans to your right.", 1, 1, 7, 1, " blocks")
        .min(1).under(shape, Shape.ROW);
    private final NumberSetting radius = new NumberSetting("Radius",
        "How far the patch reaches. One makes a plus and one and a half a three by three square."
            + " Two adds a tip to each side.", 1, 1, 4, 0.5, " blocks")
        .min(0).under(shape, Shape.PATCH);
    private final NumberSetting height = new NumberSetting("Height",
        "How many blocks tall the trail is. One lays it at your feet and more build a wall as high as you reach.",
        1, 1, 8, 1, " blocks").min(1);
    private final NumberSetting spacing = new NumberSetting("Spacing",
        "Blocks walked between one part of the trail and the next. Zero leaves no gaps.",
        0, 0, 16, 1, " blocks").min(0);
    private final BoolSetting onlyOnGround = new BoolSetting("Only on ground",
        "Leaves nothing where you jumped or fell through the air.", true);

    private final SequencedSet<BlockPos> spots = new LinkedHashSet<>();
    private final WorldWatch world = new WorldWatch();
    private BlockPos last;
    private boolean stoodOnLast;

    // Steps taken since the last part went down.
    private int gap;

    public TrailMaker() {
        super("TrailMaker", "Places a block behind you as you walk.",
            "The blocks the trail is made of. Click to pick them. TNT makes a trail that TntAura lights with Light placed.",
            List.of(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.NETHERRACK, Blocks.DIRT,
                Blocks.STONE, Blocks.DEEPSLATE),
            "Outline the spots waiting to be filled.", 0, TARGET_COLOR);
        addSettings(shape, width, radius, height, spacing, onlyOnGround, blocks, range, wallsRange, perRound, delay,
            rotate, render);
        searchTags("trail", "wall behind", "block trail", "tnt trail", "road");
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
        gap = Integer.MAX_VALUE;
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

    // The block the feet just left joins the trail with the rest of its part.
    private void track() {
        BlockPos feet = mc.player.blockPosition();
        if (feet.equals(last)) {
            stoodOnLast |= mc.player.onGround();
            return;
        }
        if (last != null && (stoodOnLast || !onlyOnGround.isOn())) {
            if (gap >= spacing.getInt()) {
                lay(last, heading(last, feet));
                gap = 0;
            } else {
                gap++;
            }
        }
        last = feet;
        stoodOnLast = mc.player.onGround();
    }

    // A diagonal step or a step straight up or down takes the way you face.
    private static Direction heading(BlockPos from, BlockPos to) {
        return Direction.getNearest(to.getX() - from.getX(), 0, to.getZ() - from.getZ(), mc.player.getDirection());
    }

    private void lay(BlockPos spot, Direction heading) {
        for (BlockPos base : footprint(spot, heading)) {
            for (int i = 0; i < height.getInt(); i++) {
                spots.add(base.above(i));
            }
        }
        while (spots.size() > MAX_SPOTS) {
            spots.removeFirst();
        }
    }

    // The blocks of one part of the trail on the level of the spot.
    private List<BlockPos> footprint(BlockPos spot, Direction heading) {
        return switch (shape.getValue()) {
            case ROW -> row(spot, heading.getClockWise());
            case PATCH -> patch(spot);
        };
    }

    // The spot in the middle of a row that runs across the way you walk.
    private List<BlockPos> row(BlockPos spot, Direction right) {
        int blocks = width.getInt();
        List<BlockPos> row = new ArrayList<>(blocks);
        for (int i = -(blocks - 1) / 2; i <= blocks / 2; i++) {
            row.add(spot.relative(right, i));
        }
        return row;
    }

    // Every block on the level whose middle lies within the radius of the spot's middle.
    private List<BlockPos> patch(BlockPos spot) {
        double reach = radius.getValue();
        int span = (int) Math.floor(reach);
        List<BlockPos> patch = new ArrayList<>();
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                if (dx * dx + dz * dz <= reach * reach) {
                    patch.add(spot.offset(dx, 0, dz));
                }
            }
        }
        return patch;
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
