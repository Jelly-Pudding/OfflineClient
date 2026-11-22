package com.jellypudding.offlineclient.worldgen;

import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChunkScanner;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Passages players dug by hand. Natural caves almost never make a straight corridor of one width
// with a floor and a ceiling. Nor do they make a shaft walled on every side or a staircase that
// climbs one block a step or a zigzag cut through the rock. Every cell is judged with the chunks
// round it and a scanned chunk keeps only its own cells.
public final class DugPassages {

    public enum Kind { CORRIDOR, SHAFT, STAIRS, DIAGONAL }

    public record Find(int x, int y, int z, Kind kind, int height) {
    }

    public record Rules(int low, int high, int widest, boolean onlyAir, boolean naturalWalls, boolean corridors,
                        int minLength, int minHeight, int maxHeight, boolean shafts, int minDepth,
                        boolean waterShafts, boolean stairs, int stairSteps, int minHeadroom, int maxHeadroom,
                        boolean diagonals, int diagonalSteps, int shortestRun, int longestRun) {
    }

    private record Span(int from, int to) {
    }

    private static final class Walls {

        private int all;
        private int natural;

        private void add(BlockState state) {
            all++;
            if (NaturalBlocks.contains(state.getBlock())) {
                natural++;
            }
        }

        private boolean mostlyNatural() {
            return natural >= all * NATURAL_SHARE;
        }
    }

    private static final Direction[] AROUND = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final Direction.Axis[] FLAT_AXES = {Direction.Axis.X, Direction.Axis.Z};
    private static final int LONGEST_PATH = 256;
    // A cell is walled by its neighbours and a short run carries on across a chunk border.
    public static final int CELL_BORDER = 2;
    // The scan sees this many chunks round each chunk. A hole any wider has walls it cannot read.
    public static final int VIEW_CHUNKS = 2;
    public static final int WIDEST_SEEN = VIEW_CHUNKS * SectionPos.SECTION_SIZE - 1;
    private static final int SIDE = SectionPos.SECTION_SIZE;
    private static final int COLUMNS = SIDE * SIDE;
    // Seven in ten of the blocks round a dug passage are natural ground.
    private static final double NATURAL_SHARE = 0.7;

    private final SeenBlocks world;
    private final Rules rules;
    private final int baseX;
    private final int baseZ;
    private final int from;
    private final int to;
    // Every cell found. A zigzag wins over the straight runs it is made of.
    private final Long2ObjectOpenHashMap<Find> cells = new Long2ObjectOpenHashMap<>();

    private DugPassages(SeenBlocks world, ChunkPos chunk, int minY, int maxY, Rules rules) {
        this.world = world;
        this.rules = rules;
        this.baseX = chunk.getMinBlockX();
        this.baseZ = chunk.getMinBlockZ();
        this.from = Math.max(rules.low(), minY + 1);
        this.to = Math.min(rules.high(), maxY - 2);
    }

    public static void scan(ChunkScanner.View view, Rules rules, List<Find> out) {
        new DugPassages(view::get, view.pos(), view.minY(), view.maxY(), rules).collect(out);
    }

    private void collect(List<Find> out) {
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

    // Nought when it has no floor or no ceiling or its height is out of range.
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

    private boolean walled(int x, int y, int z, int h) {
        for (int i = 0; i < h; i++) {
            if (open(x, y + i, z)) {
                return false;
            }
        }
        return true;
    }

    private void mark(int x, int y, int z, Kind kind, int h) {
        if (rules.naturalWalls() && !dug(x, y, z, h)) {
            return;
        }
        long key = BlockPos.asLong(x, y, z);
        Find known = cells.get(key);
        if (known == null || kind.ordinal() > known.kind().ordinal()) {
            cells.put(key, new Find(x, y, z, kind, h));
        }
    }

    // Dug out of natural ground. The world carves its mineshafts and strongholds out as cave
    // air and a block a player breaks leaves plain air. Most of the blocks round the cell and
    // under and over it are natural.
    private boolean dug(int x, int y, int z, int h) {
        Walls walls = new Walls();
        walls.add(world.at(x, y - 1, z));
        walls.add(world.at(x, y + h, z));
        for (int i = 0; i < h; i++) {
            if (world.at(x, y + i, z).is(Blocks.CAVE_AIR)) {
                return false;
            }
            for (Direction side : AROUND) {
                int nx = x + side.getStepX();
                int nz = z + side.getStepZ();
                if (!open(nx, y + i, nz)) {
                    walls.add(world.at(nx, y + i, nz));
                }
            }
        }
        return walls.mostlyNatural();
    }

    // Columns of holes closed on every side. Each column is one find at its foot.
    private void shafts(List<Find> out) {
        int[] start = new int[COLUMNS];
        Arrays.fill(start, Integer.MIN_VALUE);
        for (int y = from; y <= to + 1; y++) {
            boolean[] holes = y <= to ? holes(y) : new boolean[COLUMNS];
            for (int i = 0; i < COLUMNS; i++) {
                if (holes[i] && start[i] == Integer.MIN_VALUE) {
                    start[i] = y;
                } else if (!holes[i] && start[i] != Integer.MIN_VALUE) {
                    if (y - start[i] >= rules.minDepth()) {
                        out.add(new Find(baseX + i / SIDE, start[i], baseZ + i % SIDE, Kind.SHAFT, y - start[i]));
                    }
                    start[i] = Integer.MIN_VALUE;
                }
            }
        }
    }

    private boolean[] holes(int y) {
        boolean[] holes = new boolean[COLUMNS];
        LongSet seen = new LongOpenHashSet();
        for (int i = 0; i < COLUMNS; i++) {
            int x = baseX + i / SIDE;
            int z = baseZ + i % SIDE;
            if (seen.contains(BlockPos.asLong(x, y, z)) || !shaftOpen(x, y, z)) {
                continue;
            }
            LongArrayList patch = patch(x, y, z, seen);
            if (patch == null || !walledHole(patch)) {
                continue;
            }
            for (int k = 0; k < patch.size(); k++) {
                int cx = BlockPos.getX(patch.getLong(k)) - baseX;
                int cz = BlockPos.getZ(patch.getLong(k)) - baseZ;
                if (cx >= 0 && cx < SIDE && cz >= 0 && cz < SIDE) {
                    holes[cx * SIDE + cz] = true;
                }
            }
        }
        return holes;
    }

    // Null when the patch spreads further from the chunk than a hole of Widest could. Every cell it
    // reaches is seen.
    private LongArrayList patch(int x, int y, int z, LongSet seen) {
        int reach = rules.widest() + 1;
        LongArrayList patch = new LongArrayList();
        LongArrayList waiting = new LongArrayList();
        boolean spreads = false;
        long first = BlockPos.asLong(x, y, z);
        seen.add(first);
        waiting.add(first);
        while (!waiting.isEmpty()) {
            long cell = waiting.removeLong(waiting.size() - 1);
            patch.add(cell);
            for (Direction side : AROUND) {
                int nx = BlockPos.getX(cell) + side.getStepX();
                int nz = BlockPos.getZ(cell) + side.getStepZ();
                if (!shaftOpen(nx, y, nz)) {
                    continue;
                }
                if (nx < baseX - reach || nx >= baseX + SIDE + reach || nz < baseZ - reach
                    || nz >= baseZ + SIDE + reach) {
                    spreads = true;
                } else if (seen.add(BlockPos.asLong(nx, y, nz))) {
                    waiting.add(BlockPos.asLong(nx, y, nz));
                }
            }
        }
        return spreads ? null : patch;
    }

    // A whole rectangle no wider than Widest either way. With Natural walls it holds no cave
    // air and its sides are mostly natural ground. Every open cell beside it is part of it.
    private boolean walledHole(LongArrayList patch) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int k = 0; k < patch.size(); k++) {
            long cell = patch.getLong(k);
            minX = Math.min(minX, BlockPos.getX(cell));
            maxX = Math.max(maxX, BlockPos.getX(cell));
            minZ = Math.min(minZ, BlockPos.getZ(cell));
            maxZ = Math.max(maxZ, BlockPos.getZ(cell));
        }
        int across = maxX - minX + 1;
        int along = maxZ - minZ + 1;
        if (across > rules.widest() || along > rules.widest() || patch.size() != across * along) {
            return false;
        }
        if (!rules.naturalWalls()) {
            return true;
        }
        Walls walls = new Walls();
        for (int k = 0; k < patch.size(); k++) {
            long cell = patch.getLong(k);
            int x = BlockPos.getX(cell);
            int y = BlockPos.getY(cell);
            int z = BlockPos.getZ(cell);
            if (world.at(x, y, z).is(Blocks.CAVE_AIR)) {
                return false;
            }
            for (Direction side : AROUND) {
                int nx = x + side.getStepX();
                int nz = z + side.getStepZ();
                if (!shaftOpen(nx, y, nz)) {
                    walls.add(world.at(nx, y, nz));
                }
            }
        }
        return walls.mostlyNatural();
    }

    // A cell of a straight tunnel whose run along the axis is long enough. Every slice of the
    // run spans the same cells across.
    private void corridor(int x, int y, int z) {
        for (Direction.Axis axis : FLAT_AXES) {
            Span span = across(x, y, z, axis);
            if (span == null) {
                continue;
            }
            int run = 1;
            int dx = axis == Direction.Axis.X ? 1 : 0;
            int dz = 1 - dx;
            for (int way = -1; way <= 1 && run < rules.minLength(); way += 2) {
                for (int step = 1; run < rules.minLength()
                    && span.equals(across(x + way * dx * step, y, z + way * dz * step, axis)); step++) {
                    run++;
                }
            }
            if (run >= rules.minLength()) {
                mark(x, y, z, Kind.CORRIDOR, opening(x, y, z, rules.minHeight(), rules.maxHeight()));
            }
        }
    }

    // The cells across a tunnel through a cell running along the axis. Each one has a floor and
    // a ceiling in range and walls close both ends within Widest. Null for anything else.
    private Span across(int x, int y, int z, Direction.Axis axis) {
        int h = opening(x, y, z, rules.minHeight(), rules.maxHeight());
        if (h == 0) {
            return null;
        }
        // A tunnel along X spreads across Z and one along Z across X.
        int stepX = axis == Direction.Axis.X ? 0 : 1;
        int stepZ = 1 - stepX;
        int back = beside(x, y, z, h, -stepX, -stepZ);
        int ahead = beside(x, y, z, h, stepX, stepZ);
        if (back < 0 || ahead < 0 || 1 + back + ahead > rules.widest()) {
            return null;
        }
        int at = axis == Direction.Axis.X ? z : x;
        return new Span(at - back, at + ahead);
    }

    // Minus one when something other than a wall ends it or it runs on past Widest.
    private int beside(int x, int y, int z, int h, int stepX, int stepZ) {
        int last = h;
        for (int k = 1; k <= rules.widest(); k++) {
            int cx = x + stepX * k;
            int cz = z + stepZ * k;
            int next = opening(cx, y, cz, rules.minHeight(), rules.maxHeight());
            if (next == 0) {
                return walled(cx, y, cz, last) ? k - 1 : -1;
            }
            last = next;
        }
        return -1;
    }

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

    private static Direction move(List<BlockPos> path, int i) {
        BlockPos a = path.get(i);
        BlockPos b = path.get(i + 1);
        return Direction.getNearest(b.getX() - a.getX(), 0, b.getZ() - a.getZ(), Direction.NORTH);
    }
}
