package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.JoinedCells;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChunkScanner;
import com.jellypudding.offlineclient.worldgen.SeenBlocks;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

// Highlights passages players dug by hand. Natural caves almost never make a corridor one
// block wide with a floor and a ceiling. Nor do they make a shaft walled on all four sides or
// a staircase that climbs one block a step or a zigzag cut through the rock.
public final class TunnelEsp extends Module {

    private enum Kind { CORRIDOR, SHAFT, STAIRS, DIAGONAL }

    // A dug cell or the foot of a shaft with how tall its opening is.
    private record Find(int x, int y, int z, Kind kind, int height) {
    }

    // What the scan looks for. It reads these once a chunk.
    private record Rules(int low, int high, boolean onlyAir, boolean corridors, int minLength, int minHeight,
                         int maxHeight, boolean shafts, int minDepth, boolean waterShafts, boolean stairs,
                         int stairSteps, int minHeadroom, int maxHeadroom, boolean diagonals, int diagonalSteps,
                         int shortestRun, int longestRun) {
    }

    private static final Direction[] AROUND = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    // A path is followed no further than this each way from where its trace began.
    private static final int LONGEST_PATH = 256;
    // A cell is walled by its neighbours and a short run carries on across a chunk border.
    private static final int CELL_BORDER = 2;

    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to search around you.", 4, 1, 8, 1, " chunks").max(ChunkMap.MAX_VIEW_DISTANCE);
    private final NumberSetting bottom = new NumberSetting("Bottom",
        "Lowest height to search.", -64, -64, 320, 4).min(-2048).max(2048);
    private final NumberSetting top = new NumberSetting("Top",
        "Highest height to search.", 320, -64, 320, 4).min(-2048).max(2048);
    private final BoolSetting onlyAir = new BoolSetting("Only air",
        "Counts only air as open. Torches and rails then block a passage.", false);
    private final BoolSetting corridors = new BoolSetting("Corridors",
        "Marks straight tunnels one block wide.", true);
    private final NumberSetting minLength = new NumberSetting("Min length",
        "How many blocks long a tunnel must be.", 3, 1, 32, 1, " blocks").min(1).under(corridors);
    private final NumberSetting minHeight = new NumberSetting("Min height",
        "How low a tunnel or zigzag may be.", 2, 1, 6, 1, " blocks").min(1);
    private final NumberSetting maxHeight = new NumberSetting("Max height",
        "How tall a tunnel or zigzag may be. Below Min height it counts as Min height.", 3, 1, 6, 1, " blocks")
        .min(1);
    private final BoolSetting shafts = new BoolSetting("Shafts",
        "Marks upright holes one block across with walls on all four sides.", true);
    private final NumberSetting minDepth = new NumberSetting("Min depth",
        "How deep a shaft must be.", 4, 2, 64, 1, " blocks").min(2).under(shafts);
    private final BoolSetting waterShafts = new BoolSetting("Water shafts",
        "Also marks shafts full of water such as drops and bubble lifts.", true).under(shafts);
    private final BoolSetting staircases = new BoolSetting("Staircases",
        "Marks stairs one block wide that climb a block with every step.", true);
    private final NumberSetting stairSteps = new NumberSetting("Stair steps",
        "How many steps a staircase must have.", 3, 2, 32, 1).min(2).under(staircases);
    private final NumberSetting minHeadroom = new NumberSetting("Min headroom",
        "How low the space over a step may be.", 3, 2, 6, 1, " blocks").min(2).under(staircases);
    private final NumberSetting maxHeadroom = new NumberSetting("Max headroom",
        "How tall the space over a step may be. Below Min headroom it counts as Min headroom.", 5, 2, 8, 1,
        " blocks").min(2).under(staircases);
    private final BoolSetting diagonals = new BoolSetting("Diagonals",
        "Marks tunnels that zigzag at an angle through the rock.", true);
    private final NumberSetting diagonalSteps = new NumberSetting("Diagonal steps",
        "How many straight runs a zigzag must have.", 3, 2, 32, 1).min(2).under(diagonals);
    private final NumberSetting shortestRun = new NumberSetting("Shortest run",
        "How few blocks a zigzag may go straight before it turns.", 1, 1, 8, 1, " blocks").min(1)
        .under(diagonals);
    private final NumberSetting longestRun = new NumberSetting("Longest run",
        "How many blocks a zigzag may go straight before it turns. Below Shortest run it counts as Shortest run.",
        3, 1, 8, 1, " blocks").min(1).under(diagonals);
    private final ChoiceListSetting skipped = new ChoiceListSetting("Skipped dimensions",
        "Dimensions where nothing is searched. Join a world to fill the list.", TunnelEsp::dimensions);
    private final BoolSetting fullHeight = new BoolSetting("Full height",
        "Draws tunnels and steps as tall as their opening. Shafts are always drawn whole.", false);
    private final NumberSetting height = new NumberSetting("Box height",
        "How tall the drawn box of a tunnel or step is.", 0.15, 0.05, 1, 0.05).min(0.01).unless(fullHeight);
    private final BoolSetting connected = new BoolSetting("Connected",
        "Draws touching boxes as one shape without the faces they share.", true);
    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final ColorSetting corridorColor = new ColorSetting("Corridor colour",
        "Colour of straight tunnels.", 39, false).under(corridors);
    private final ColorSetting shaftColor = new ColorSetting("Shaft colour",
        "Colour of shafts.", 190, false).under(shafts);
    private final ColorSetting stairsColor = new ColorSetting("Stairs colour",
        "Colour of staircases.", 120, false).under(staircases);
    private final ColorSetting diagonalColor = new ColorSetting("Diagonal colour",
        "Colour of zigzag tunnels.", 280, false).under(diagonals);

    private final ChunkScanner<Find> scanner = new ChunkScanner<Find>(2).borderReach(CELL_BORDER);

    private final JoinedCells<Find> joined = new JoinedCells<>(find -> BlockPos.asLong(find.x(), find.y(), find.z()));

    // The rules the cached chunks were scanned with.
    private Rules rules;

    public TunnelEsp() {
        super("TunnelESP", "Highlights hand dug tunnels shafts and staircases underground.", Category.HUNTING);
        addSettings(range, bottom, top, onlyAir, corridors, minLength, minHeight, maxHeight, shafts, minDepth,
            waterShafts, staircases, stairSteps, minHeadroom, maxHeadroom, diagonals, diagonalSteps, shortestRun,
            longestRun, skipped, fullHeight, height, connected);
        addSettings(style.settings());
        addSettings(corridorColor, shaftColor, stairsColor, diagonalColor);
        searchTags("tunnel", "base finder", "corridor", "hole", "shaft", "staircase", "stairs", "diagonal");
    }

    private static List<String> dimensions() {
        ClientPacketListener connection = mc.getConnection();
        return connection == null ? List.of()
            : connection.levels().stream().map(level -> level.identifier().toString()).sorted().toList();
    }

    @Override
    public String getSuffix() {
        return count(scanner.size());
    }

    @Override
    protected void onEnable() {
        forget();
    }

    @Override
    protected void onDisable() {
        forget();
    }

    private void forget() {
        scanner.reset();
        joined.clear();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        scanner.markChanged(event.getPacket());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (skipped.contains(mc.level.dimension().identifier().toString())) {
            forget();
            return;
        }
        // Each max counts as at least its min.
        Rules now = new Rules(bottom.getInt(), top.getInt(), onlyAir.isOn(), corridors.isOn(), minLength.getInt(),
            minHeight.getInt(), Math.max(minHeight.getInt(), maxHeight.getInt()), shafts.isOn(), minDepth.getInt(),
            waterShafts.isOn(), staircases.isOn(), stairSteps.getInt(), minHeadroom.getInt(),
            Math.max(minHeadroom.getInt(), maxHeadroom.getInt()), diagonals.isOn(), diagonalSteps.getInt(),
            shortestRun.getInt(), Math.max(shortestRun.getInt(), longestRun.getInt()));
        // The scan reads the rules once a chunk. A change has to throw the cache away.
        if (!now.equals(rules)) {
            rules = now;
            scanner.reset();
        }
        Rules scanRules = rules;
        scanner.update(range.getInt(), (view, out) ->
            new Passages(view::get, view.pos(), view.minY(), view.maxY(), scanRules).scan(out));
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        List<Find> finds = scanner.results();
        boolean join = connected.isOn();
        if (join) {
            joined.update(finds);
        }
        for (Find find : finds) {
            double tall = find.kind() == Kind.SHAFT || fullHeight.isOn() ? find.height() : height.getValue();
            AABB box = new AABB(find.x(), find.y(), find.z(), find.x() + 1, find.y() + tall, find.z() + 1);
            int color = colorOf(find.kind());
            style.drawJoined(batch, box, join ? joined.hiddenSides(find) : 0, color, color, true);
        }
    }

    private int colorOf(Kind kind) {
        return switch (kind) {
            case CORRIDOR -> corridorColor.getColor();
            case SHAFT -> shaftColor.getColor();
            case STAIRS -> stairsColor.getColor();
            case DIAGONAL -> diagonalColor.getColor();
        };
    }

    // The dug passages of one scanned chunk. Every cell is judged with the chunks round it
    // and the chunk keeps only its own cells.
    private static final class Passages {

        private final SeenBlocks world;
        private final Rules rules;
        private final int baseX;
        private final int baseZ;
        private final int from;
        private final int to;
        // Every cell found. A zigzag wins over the straight runs it is made of.
        private final Long2ObjectOpenHashMap<Find> cells = new Long2ObjectOpenHashMap<>();

        private Passages(SeenBlocks world, ChunkPos chunk, int minY, int maxY, Rules rules) {
            this.world = world;
            this.rules = rules;
            this.baseX = chunk.getMinBlockX();
            this.baseZ = chunk.getMinBlockZ();
            this.from = Math.max(rules.low(), minY + 1);
            this.to = Math.min(rules.high(), maxY - 2);
        }

        private void scan(List<Find> out) {
            if (rules.shafts()) {
                shafts(out);
            }
            LongSet traced = new LongOpenHashSet();
            for (int x = baseX; x < baseX + 16; x++) {
                for (int z = baseZ; z < baseZ + 16; z++) {
                    for (int y = from; y <= to; y++) {
                        if (!open(x, y, z) || !solid(x, y - 1, z)) {
                            continue;
                        }
                        if (rules.corridors()) {
                            corridor(x, y, z);
                        }
                        if (rules.stairs()) {
                            staircase(x, y, z);
                        }
                        if (rules.diagonals()) {
                            diagonal(x, y, z, traced);
                        }
                    }
                }
            }
            for (Find find : cells.values()) {
                if (find.x() >= baseX && find.x() < baseX + 16 && find.z() >= baseZ && find.z() < baseZ + 16) {
                    out.add(find);
                }
            }
        }

        private boolean open(int x, int y, int z) {
            BlockState state = world.at(x, y, z);
            return rules.onlyAir() ? state.isAir() : !BlockUtil.blocksMotion(state) && state.getFluidState().isEmpty();
        }

        private boolean solid(int x, int y, int z) {
            BlockState state = world.at(x, y, z);
            return BlockUtil.blocksMotion(state) && state.getFluidState().isEmpty();
        }

        // Water and bubble columns fill many shafts players dug.
        private boolean shaftOpen(int x, int y, int z) {
            return open(x, y, z)
                || rules.waterShafts() && world.at(x, y, z).getFluidState().getType().isSame(Fluids.WATER);
        }

        // The opening over a floor or nought when it has no floor or no ceiling or its height
        // is out of range.
        private int opening(int x, int y, int z, int least, int most) {
            if (!solid(x, y - 1, z)) {
                return 0;
            }
            int h = 0;
            while (h <= most && open(x, y + h, z)) {
                h++;
            }
            return h >= least && h <= most && solid(x, y + h, z) ? h : 0;
        }

        // True when no cell beside an opening is open.
        private boolean walled(int x, int y, int z, int h) {
            for (int i = 0; i < h; i++) {
                if (open(x, y + i, z)) {
                    return false;
                }
            }
            return true;
        }

        private void mark(int x, int y, int z, Kind kind, int h) {
            long key = BlockPos.asLong(x, y, z);
            Find known = cells.get(key);
            if (known == null || kind.ordinal() > known.kind().ordinal()) {
                cells.put(key, new Find(x, y, z, kind, h));
            }
        }

        // Columns walled on all four sides. The whole column is one find at its foot.
        private void shafts(List<Find> out) {
            for (int x = baseX; x < baseX + 16; x++) {
                for (int z = baseZ; z < baseZ + 16; z++) {
                    int start = Integer.MIN_VALUE;
                    for (int y = from; y <= to + 1; y++) {
                        boolean shaft = y <= to && shaftCell(x, y, z);
                        if (shaft && start == Integer.MIN_VALUE) {
                            start = y;
                        } else if (!shaft && start != Integer.MIN_VALUE) {
                            if (y - start >= rules.minDepth()) {
                                out.add(new Find(x, start, z, Kind.SHAFT, y - start));
                            }
                            start = Integer.MIN_VALUE;
                        }
                    }
                }
            }
        }

        private boolean shaftCell(int x, int y, int z) {
            if (!shaftOpen(x, y, z)) {
                return false;
            }
            for (Direction side : AROUND) {
                if (shaftOpen(x + side.getStepX(), y, z + side.getStepZ())) {
                    return false;
                }
            }
            return true;
        }

        // A cell walled on two opposite sides whose run along the other axis is long enough.
        private void corridor(int x, int y, int z) {
            for (Direction.Axis axis : new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z}) {
                int h = along(x, y, z, axis);
                if (h == 0) {
                    continue;
                }
                int run = 1;
                int dx = axis == Direction.Axis.X ? 1 : 0;
                int dz = 1 - dx;
                for (int way = -1; way <= 1 && run < rules.minLength(); way += 2) {
                    for (int step = 1; run < rules.minLength() && along(x + way * dx * step, y, z + way * dz * step,
                        axis) > 0; step++) {
                        run++;
                    }
                }
                if (run >= rules.minLength()) {
                    mark(x, y, z, Kind.CORRIDOR, h);
                }
            }
        }

        // The opening of a cell whose sides across the axis are walled or nought.
        private int along(int x, int y, int z, Direction.Axis axis) {
            int h = opening(x, y, z, rules.minHeight(), rules.maxHeight());
            if (h == 0) {
                return 0;
            }
            boolean walls = axis == Direction.Axis.X
                ? walled(x, y, z - 1, h) && walled(x, y, z + 1, h)
                : walled(x - 1, y, z, h) && walled(x + 1, y, z, h);
            return walls ? h : 0;
        }

        // A cell that starts or carries on a staircase in one of the four directions.
        private void staircase(int x, int y, int z) {
            for (Direction way : AROUND) {
                int h = step(x, y, z, way);
                if (h == 0) {
                    continue;
                }
                int steps = 1;
                for (int k = 1; steps < rules.stairSteps() && step(x + way.getStepX() * k, y + k,
                    z + way.getStepZ() * k, way) > 0; k++) {
                    steps++;
                }
                for (int k = 1; steps < rules.stairSteps() && step(x - way.getStepX() * k, y - k,
                    z - way.getStepZ() * k, way) > 0; k++) {
                    steps++;
                }
                if (steps >= rules.stairSteps()) {
                    mark(x, y, z, Kind.STAIRS, h);
                }
            }
        }

        // The headroom of a step walled on both sides across the way it climbs or nought.
        private int step(int x, int y, int z, Direction way) {
            int h = opening(x, y, z, rules.minHeadroom(), rules.maxHeadroom());
            if (h == 0) {
                return 0;
            }
            Direction side = way.getClockWise();
            return walled(x + side.getStepX(), y, z + side.getStepZ(), h)
                && walled(x - side.getStepX(), y, z - side.getStepZ(), h) ? h : 0;
        }

        // The ways on from a cell of a one wide path. The path goes on two sides or ends at one and
        // the other sides are walled. A dug path keeps one height where a thin cave rises and falls.
        // Null for any other cell.
        private List<Direction> pathWays(int x, int y, int z) {
            int h = opening(x, y, z, rules.minHeight(), rules.maxHeight());
            if (h == 0) {
                return null;
            }
            List<Direction> ways = new ArrayList<>(2);
            for (Direction side : AROUND) {
                int nx = x + side.getStepX();
                int nz = z + side.getStepZ();
                int next = opening(nx, y, nz, rules.minHeight(), rules.maxHeight());
                if (next == h) {
                    ways.add(side);
                } else if (next > 0 || !walled(nx, y, nz, h)) {
                    return null;
                }
            }
            return ways.isEmpty() || ways.size() > 2 ? null : ways;
        }

        // Follows the path through a turning cell both ways and marks the zigzags on it.
        private void diagonal(int x, int y, int z, LongSet traced) {
            List<Direction> ways = pathWays(x, y, z);
            if (ways == null || ways.size() < 2 || ways.get(0).getAxis() == ways.get(1).getAxis()
                || !traced.add(BlockPos.asLong(x, y, z))) {
                return;
            }
            List<BlockPos> back = follow(x, y, z, ways.get(0), traced);
            List<BlockPos> ahead = follow(x, y, z, ways.get(1), traced);
            List<BlockPos> path = new ArrayList<>(back.reversed());
            path.add(new BlockPos(x, y, z));
            path.addAll(ahead);
            zigzags(path);
        }

        // The cells of the path one way from a cell until it ends or splits.
        private List<BlockPos> follow(int x, int y, int z, Direction first, LongSet traced) {
            List<BlockPos> cells = new ArrayList<>();
            Direction way = first;
            int cx = x;
            int cz = z;
            while (cells.size() < LONGEST_PATH) {
                cx += way.getStepX();
                cz += way.getStepZ();
                List<Direction> ways = pathWays(cx, y, cz);
                if (ways == null) {
                    break;
                }
                cells.add(new BlockPos(cx, y, cz));
                traced.add(BlockPos.asLong(cx, y, cz));
                if (ways.size() < 2) {
                    break;
                }
                Direction cameFrom = way.getOpposite();
                way = ways.get(0) == cameFrom ? ways.get(1) : ways.get(0);
            }
            return cells;
        }

        // Marks every stretch of the path that zigzags between two directions. The runs inside a
        // stretch go straight for the shortest to the longest run. The runs at its ends are cut to
        // the longest where a corridor carries on.
        private void zigzags(List<BlockPos> path) {
            List<int[]> runs = runsOf(path);
            int first = 0;
            while (first < runs.size()) {
                int last = lastRun(path, runs, first);
                if (last - first + 1 >= rules.diagonalSteps()) {
                    int[] head = runs.get(first);
                    int[] tail = runs.get(last);
                    int start = head[0] + head[1] - Math.min(head[1], rules.longestRun());
                    int end = tail[0] + Math.min(tail[1], rules.longestRun());
                    for (int i = start; i <= end; i++) {
                        BlockPos cell = path.get(i);
                        mark(cell.getX(), cell.getY(), cell.getZ(), Kind.DIAGONAL,
                            opening(cell.getX(), cell.getY(), cell.getZ(), rules.minHeight(), rules.maxHeight()));
                    }
                }
                first = Math.max(first + 1, last);
            }
        }

        // The straight runs of a path as the first move of each and how many moves it holds.
        private static List<int[]> runsOf(List<BlockPos> path) {
            List<int[]> runs = new ArrayList<>();
            for (int i = 0; i < path.size() - 1; i++) {
                if (i == 0 || move(path, i) != move(path, i - 1)) {
                    runs.add(new int[] {i, 1});
                } else {
                    runs.getLast()[1]++;
                }
            }
            return runs;
        }

        // The last run of the zigzag that starts at a run. Its runs go one of the same two ways
        // and each run inside it is in range.
        private int lastRun(List<BlockPos> path, List<int[]> runs, int first) {
            Direction one = move(path, runs.get(first)[0]);
            Direction two = null;
            int last = first;
            while (last + 1 < runs.size()) {
                Direction next = move(path, runs.get(last + 1)[0]);
                if (two == null && next.getAxis() != one.getAxis()) {
                    two = next;
                } else if (next != one && next != two) {
                    break;
                }
                int length = runs.get(last)[1];
                if (last > first && (length < rules.shortestRun() || length > rules.longestRun())) {
                    break;
                }
                last++;
            }
            return last;
        }

        // The way the path goes from one cell to the next.
        private static Direction move(List<BlockPos> path, int i) {
            BlockPos a = path.get(i);
            BlockPos b = path.get(i + 1);
            return Direction.getNearest(b.getX() - a.getX(), 0, b.getZ() - a.getZ(), Direction.NORTH);
        }
    }
}
