package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.config.Blueprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

// The picture a map file holds. A map shows the top block of every column of its square and the
// top layer of a file is all of it that counts. A file one row longer than a map carries a line
// on its north side that shades the first row of the map the way the file wants. The picture
// lies in one layer with its west column at x nought and its first map row at z nought.
public final class MapPicture {

    public static final int SIDE = MapGrid.SIDE;

    // A file this long from north to south carries a north line.
    private static final int WITH_LINE = SIDE + 1;
    // The most cells a picture covers. That is a map square and the line north of it.
    public static final int MOST_CELLS = WITH_LINE * SIDE;
    // A file wider or longer than this is no map and is not looked over.
    private static final int MOST_ACROSS = 2 * WITH_LINE;
    // Nor is one taller than this.
    private static final int MOST_LAYERS = 400;
    // A block of the row waited for is clicked on the floor under it or on the block behind it or
    // on the block beside it towards the middle of the strip.
    private static final List<Direction> CLICKED_ON = List.of(Direction.DOWN, Direction.NORTH, Direction.WEST);

    // A picture or the words that follow the file name to say why there is none.
    public record Read(MapPicture picture, String trouble) {

        static Read refused(String trouble) {
            return new Read(null, trouble);
        }
    }

    // How far a trip gets along the walk. The needs say how many of each kind the cells of the
    // trip take and cells says how many cells of the walk it covers.
    public record Trip<K>(Map<K, Integer> needs, int cells) {
    }

    private final Blueprint blueprint;
    private final int firstRow;

    private MapPicture(Blueprint blueprint, int firstRow) {
        this.blueprint = blueprint;
        this.firstRow = firstRow;
    }

    // One layer of cells. The north line is row minus one where there is one.
    public Blueprint blueprint() {
        return blueprint;
    }

    // The northernmost row. Minus one with a north line and nought without.
    public int firstRow() {
        return firstRow;
    }

    public int rows() {
        return SIDE - firstRow;
    }

    // True for a cell of the map square itself. A north line lies outside it.
    public static boolean inSquare(int x, int z) {
        return x >= 0 && x < SIDE && z >= 0 && z < SIDE;
    }

    // The picture in a file. The top block of each column is taken and the rest is left out.
    public static Read of(Blueprint file) {
        Vec3i size = file.size();
        if (size.getX() > MOST_ACROSS || size.getZ() > MOST_ACROSS || size.getY() > MOST_LAYERS) {
            return Read.refused(notAMap(size.getX(), size.getZ()));
        }
        Vec3i min = file.min();
        int across = size.getX();
        BlockState[] tops = new BlockState[across * size.getZ()];
        int[] heights = new int[tops.length];
        // The cells come from the bottom layer up. The last block of a column is its top.
        file.forEach((x, y, z, state) -> {
            if (state != null && !state.isAir()) {
                int column = (z - min.getZ()) * across + x - min.getX();
                tops[column] = state;
                heights[column] = y;
            }
        });
        int west = Integer.MAX_VALUE;
        int east = Integer.MIN_VALUE;
        int north = Integer.MAX_VALUE;
        int south = Integer.MIN_VALUE;
        for (int column = 0; column < tops.length; column++) {
            if (tops[column] != null) {
                west = Math.min(west, column % across);
                east = Math.max(east, column % across);
                north = Math.min(north, column / across);
                south = Math.max(south, column / across);
            }
        }
        int wide = east - west + 1;
        int length = south - north + 1;
        if (wide != SIDE || length != SIDE && length != WITH_LINE) {
            return Read.refused(notAMap(wide, length));
        }
        int firstRow = length == WITH_LINE ? -1 : 0;
        int height = Integer.MIN_VALUE;
        Set<String> unplaceable = new LinkedHashSet<>();
        for (int column = 0; column < tops.length; column++) {
            BlockState top = tops[column];
            if (top == null) {
                continue;
            }
            if (height == Integer.MIN_VALUE) {
                height = heights[column];
            } else if (heights[column] != height) {
                return Read.refused("is a staircased map. MapPrinter prints flat maps where every block of the"
                    + " picture sits at one height");
            }
            if (StateMatch.itemFor(top) == null || StateMatch.comesWithOtherHalf(top)) {
                unplaceable.add(BlockUtil.blockName(top.getBlock()));
            }
        }
        if (!unplaceable.isEmpty()) {
            return Read.refused("holds " + Tally.firstFew(unplaceable) + " which nobody places by hand");
        }
        Blueprint.Builder builder = new Blueprint.Builder(file.name(), new Vec3i(0, 0, firstRow),
            new Vec3i(SIDE, 1, SIDE - firstRow), file.palette().size());
        for (int z = 0; z < length; z++) {
            for (int x = 0; x < SIDE; x++) {
                BlockState top = tops[(north + z) * across + west + x];
                if (top != null) {
                    builder.set(x, 0, z + firstRow, builder.kind(top));
                }
            }
        }
        return new Read(new MapPicture(builder.build(), firstRow), null);
    }

    private static String notAMap(int wide, int length) {
        return "is " + wide + " by " + length + " blocks across. A map file is " + SIDE + " by " + SIDE + " or "
            + SIDE + " by " + WITH_LINE + " with a line on its north side";
    }

    // How high above the floor the feet stand on the highest block of the picture.
    public double standHeight() {
        double highest = 0;
        for (BlockState kind : blueprint.palette()) {
            VoxelShape shape = kind.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (!shape.isEmpty()) {
                highest = Math.max(highest, shape.max(Direction.Axis.Y));
            }
        }
        return highest;
    }

    // Columns a strip spans. Odd for a middle column to walk along. The walk stands in the middle
    // column anywhere from the middle of the cell one row behind the row it waits for to the far
    // edge of that row. From every such point each block of the row has a click the placer would
    // make with the range given and the block clicked on nearer than the server's limit. The
    // blocks beside it towards the middle and behind it are down by then. The feet stand at the
    // height given.
    public static int stripWidth(double range, double serverLimit, double feet) {
        int half = 0;
        while (half < SIDE / 2 && clickable(half + 1, range, serverLimit, feet)) {
            half++;
        }
        return 2 * half + 1;
    }

    // The middle column runs from x nought to one and the stand cell from z nought to one. The
    // worst point lies level with the middle of that cell on its side furthest from the block.
    private static boolean clickable(int across, double range, double serverLimit, double feet) {
        Vec3 eye = new Vec3(0, feet + Avatar.DEFAULT_EYE_HEIGHT, 0.5);
        BlockPos block = new BlockPos(across, 0, 1);
        for (Direction side : CLICKED_ON) {
            BlockPos beside = block.relative(side);
            if (BlockUtil.reachTo(beside, eye) >= serverLimit) {
                continue;
            }
            for (Vec3 spot : StatePlacer.spots(beside, side.getOpposite(), false)) {
                if (eye.distanceTo(spot) <= range) {
                    return true;
                }
            }
        }
        return false;
    }

    // How many strips of that width cover the square.
    public static int strips(int width) {
        return (SIDE + width - 1) / width;
    }

    // The west column of a strip and the one past its east column.
    public static int stripWest(int strip, int width) {
        return strip * width;
    }

    public static int stripEast(int strip, int width) {
        return Math.min(SIDE, (strip + 1) * width);
    }

    // Every other strip runs from south to north.
    public static boolean southward(int strip) {
        return strip % 2 == 0;
    }

    // Every cell in the order the walk comes to it as row times the side plus x with the rows
    // counted from the first row. The square is cut into strips running north and south and the
    // strips are walked in turn with every other one back the other way.
    public int[] walk(int width) {
        int rows = rows();
        int[] order = new int[rows * SIDE];
        int at = 0;
        for (int strip = 0; strip < strips(width); strip++) {
            for (int i = 0; i < rows; i++) {
                int row = southward(strip) ? i : rows - 1 - i;
                for (int x = stripWest(strip, width); x < stripEast(strip, width); x++) {
                    order[at++] = row * SIDE + x;
                }
            }
        }
        return order;
    }

    // Goes along the walk for as long as what its cells need fits the slots. A kind takes as
    // many slots as the larger of what it needs and what is carried fills. A kind the trip does
    // not need takes none because it is put away first.
    public static <K> Trip<K> plan(List<K> walk, Map<K, Integer> carried, int slots, ToIntFunction<K> stackSize) {
        Map<K, Integer> needs = new LinkedHashMap<>();
        int used = 0;
        for (int i = 0; i < walk.size(); i++) {
            K kind = walk.get(i);
            int need = needs.getOrDefault(kind, 0);
            int held = carried.getOrDefault(kind, 0);
            int stack = Math.max(1, stackSize.applyAsInt(kind));
            int before = need == 0 ? 0 : stacks(Math.max(need, held), stack);
            int after = stacks(Math.max(need + 1, held), stack);
            if (used - before + after > slots) {
                return new Trip<>(needs, i);
            }
            used += after - before;
            needs.put(kind, need + 1);
        }
        return new Trip<>(needs, walk.size());
    }

    private static int stacks(int count, int stack) {
        return (count + stack - 1) / stack;
    }
}
