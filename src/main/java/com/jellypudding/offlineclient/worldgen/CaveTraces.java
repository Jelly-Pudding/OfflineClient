package com.jellypudding.offlineclient.worldgen;

import com.jellypudding.offlineclient.util.ChunkScanner;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Plain air left behind in cave air. Every structure and every cave carved before the cave
// generators changed is cave air inside. A block a player breaks becomes plain air through
// Level.removeBlock and NetherPortalBlock turns each portal block to plain air once its frame
// breaks. Plain air that touches cave air marks where a player dug or where a portal stood.
public final class CaveTraces {

    public enum Kind { MINED, PORTAL }

    // A trace and the cell it is kept at. The cell stays plain air whilst the trace lasts.
    public record Trace(Kind kind, BlockPos key, AABB box, int cells) {
    }

    // What the scan looks for. Shares run from nought to one.
    public record Rules(boolean mined, int digSize, int clearRadius, boolean portals, int largestWidth,
                        int largestHeight, boolean cornersOptional, boolean closedEdges, double solidShare,
                        double openShare) {
    }

    // One piece of connected plain air.
    private record Piece(LongList cells, boolean big) {
    }

    // A portal frame is at least four wide and five tall with its frame.
    private static final int FRAME_WIDTH = 4;
    private static final int FRAME_HEIGHT = 5;
    private static final int CORNERS = 4;
    // Room for the tunnel a player dug up to a portal.
    private static final int PORTAL_SLACK = 64;
    private static final Direction[] SIDES = Direction.values();
    private static final int NONE = -1;

    private final SeenBlocks world;
    private final Rules rules;
    private final LongSet ownDigs;
    private final int cap;
    // The piece each plain air cell was flooded into.
    private final Long2IntMap pieceOf = new Long2IntOpenHashMap();
    private final List<Piece> pieces = new ArrayList<>();

    private CaveTraces(SeenBlocks world, Rules rules, LongSet ownDigs) {
        this.world = world;
        this.rules = rules;
        this.ownDigs = ownDigs;
        int portalCells = rules.portals() ? rules.largestWidth() * rules.largestHeight() + PORTAL_SLACK : 0;
        this.cap = Math.max(rules.digSize(), portalCells);
        pieceOf.defaultReturnValue(NONE);
    }

    // Every trace the scanned chunk owns. A trace belongs to the chunk holding the cave air cell
    // with the smallest packed position it touches. That chunk seeds it from its own cave air
    // and always finds it.
    public static void scan(ChunkScanner.View view, Rules rules, LongSet ownDigs, List<Trace> out) {
        if (!view.complete()) {
            return;
        }
        SeenBlocks world = view::get;
        LongSet seeds = new LongOpenHashSet();
        view.forEachMatching(state -> state.is(Blocks.CAVE_AIR), (x, y, z, state) -> {
            for (Direction side : SIDES) {
                long next = BlockPos.asLong(x + side.getStepX(), y + side.getStepY(), z + side.getStepZ());
                if (world.at(BlockPos.getX(next), BlockPos.getY(next), BlockPos.getZ(next)).is(Blocks.AIR)) {
                    seeds.add(next);
                }
            }
        });
        out.addAll(find(world, rules, ownDigs, view.pos(), seeds));
    }

    // The traces a chunk owns among the pieces of plain air that grow from the seeds. A seed is
    // plain air beside cave air of the chunk.
    public static List<Trace> find(SeenBlocks world, Rules rules, LongSet ownDigs, ChunkPos chunk, LongSet seeds) {
        CaveTraces traces = new CaveTraces(world, rules, ownDigs);
        List<Trace> out = new ArrayList<>();
        IntSet judged = new IntOpenHashSet();
        for (long seed : seeds) {
            int index = traces.flood(seed);
            Piece piece = traces.pieces.get(index);
            if (judged.add(index) && !piece.big() && traces.owns(chunk, piece)) {
                traces.judge(index, out);
            }
        }
        return out;
    }

    // Judges one piece of plain air the scanned chunk owns.
    private void judge(int index, List<Trace> out) {
        Piece piece = pieces.get(index);
        for (long cell : piece.cells()) {
            if (ownDigs.contains(cell)) {
                return;
            }
        }
        List<Trace> portals = rules.portals() ? portals(piece) : List.of();
        if (!portals.isEmpty()) {
            out.addAll(portals);
        } else if (rules.mined() && piece.cells().size() <= rules.digSize() && !takenByNature(piece)
            && clear(index)) {
            out.add(dig(piece));
        }
    }

    // A lone hole walled only by blocks endermen carry such as dirt and gravel. Endermen take
    // them from cave walls whenever a player is near and loose gravel drops into the cave.
    private boolean takenByNature(Piece piece) {
        if (piece.cells().size() != 1) {
            return false;
        }
        long cell = piece.cells().getLong(0);
        for (Direction side : SIDES) {
            BlockState state = at(BlockPos.offset(cell, side));
            if (!state.isAir() && !state.is(BlockTags.ENDERMAN_HOLDABLE)) {
                return false;
            }
        }
        return true;
    }

    private boolean owns(ChunkPos chunk, Piece piece) {
        long owner = Long.MAX_VALUE;
        for (long cell : piece.cells()) {
            for (Direction side : SIDES) {
                long next = BlockPos.offset(cell, side);
                if (next < owner && at(next).is(Blocks.CAVE_AIR)) {
                    owner = next;
                }
            }
        }
        return owner != Long.MAX_VALUE && (BlockPos.getX(owner) >> 4) == chunk.x()
            && (BlockPos.getZ(owner) >> 4) == chunk.z();
    }

    private BlockState at(long cell) {
        return world.at(BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell));
    }

    private boolean plainAir(long cell) {
        return at(cell).is(Blocks.AIR);
    }

    // The piece a plain air cell belongs to. A piece that grows past the cap or out of the
    // loaded chunks is big and never judged. Floods stop where they meet a big piece.
    private int flood(long start) {
        int known = pieceOf.get(start);
        if (known != NONE) {
            return known;
        }
        int index = pieces.size();
        LongList cells = new LongArrayList();
        LongArrayList queue = new LongArrayList();
        queue.add(start);
        pieceOf.put(start, index);
        boolean big = false;
        while (!queue.isEmpty() && !big) {
            long cell = queue.removeLong(queue.size() - 1);
            cells.add(cell);
            for (Direction side : SIDES) {
                long next = BlockPos.offset(cell, side);
                BlockState state = at(next);
                int other = pieceOf.get(next);
                if (state.is(Blocks.VOID_AIR) || other != NONE && other != index && pieces.get(other).big()) {
                    big = true;
                } else if (state.is(Blocks.AIR) && other == NONE) {
                    pieceOf.put(next, index);
                    queue.add(next);
                }
            }
            big |= cells.size() + queue.size() > cap;
        }
        // Whatever was queued belongs to the same piece.
        for (long cell : queue) {
            cells.add(cell);
        }
        pieces.add(new Piece(cells, big));
        return index;
    }

    // True when no plain air of a big piece lies within the clear radius. Air of another small
    // dig nearby is another trace.
    private boolean clear(int index) {
        Piece piece = pieces.get(index);
        int radius = rules.clearRadius();
        long first = piece.cells().getLong(0);
        int minX = BlockPos.getX(first);
        int minY = BlockPos.getY(first);
        int minZ = BlockPos.getZ(first);
        int maxX = minX;
        int maxY = minY;
        int maxZ = minZ;
        for (long cell : piece.cells()) {
            minX = Math.min(minX, BlockPos.getX(cell));
            minY = Math.min(minY, BlockPos.getY(cell));
            minZ = Math.min(minZ, BlockPos.getZ(cell));
            maxX = Math.max(maxX, BlockPos.getX(cell));
            maxY = Math.max(maxY, BlockPos.getY(cell));
            maxZ = Math.max(maxZ, BlockPos.getZ(cell));
        }
        for (int x = minX - radius; x <= maxX + radius; x++) {
            for (int y = minY - radius; y <= maxY + radius; y++) {
                for (int z = minZ - radius; z <= maxZ + radius; z++) {
                    long cell = BlockPos.asLong(x, y, z);
                    if (plainAir(cell) && pieces.get(flood(cell)).big()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // A small dig kept at its lowest cell.
    private static Trace dig(Piece piece) {
        long key = piece.cells().getLong(0);
        AABB box = new AABB(BlockPos.of(key));
        for (long cell : piece.cells()) {
            BlockPos pos = BlockPos.of(cell);
            box = box.minmax(new AABB(pos));
            if (pos.getY() < BlockPos.getY(key) || pos.getY() == BlockPos.getY(key) && cell < key) {
                key = cell;
            }
        }
        return new Trace(Kind.MINED, BlockPos.of(key), box, piece.cells().size());
    }

    // The broken portals in the upright planes through a piece. Two planes cross each cell.
    private List<Trace> portals(Piece piece) {
        List<Trace> found = new ArrayList<>();
        int least = leastPortalAir();
        if (piece.cells().size() < least) {
            return found;
        }
        for (Direction.Axis axis : new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z}) {
            LongSet planes = new LongOpenHashSet();
            for (long cell : piece.cells()) {
                planes.add(axis == Direction.Axis.X ? BlockPos.getZ(cell) : BlockPos.getX(cell));
            }
            for (long plane : planes) {
                found.addAll(new Plane(axis, (int) plane, piece).portals());
            }
        }
        return found;
    }

    // The inside of the smallest frame less the blocks the solid share lets stand in it.
    private int leastPortalAir() {
        int inside = (FRAME_WIDTH - 2) * (FRAME_HEIGHT - 2);
        return (int) Math.ceil(inside * (1 - rules.solidShare()));
    }

    // One upright plane through a piece read into counts a rectangle can be tested with at once.
    // Along x the plane holds x and y at one z. Along z it holds z and y at one x.
    private final class Plane {

        private final Direction.Axis axis;
        private final int depth;
        private final int u0;
        private final int v0;
        private final int width;
        private final int height;
        // Running totals of plain air cave air obsidian other blocks and plain air open to a side.
        private final int[][] air;
        private final int[][] caveAir;
        private final int[][] obsidian;
        private final int[][] other;
        private final int[][] open;

        private Plane(Direction.Axis axis, int depth, Piece piece) {
            this.axis = axis;
            this.depth = depth;
            int minU = Integer.MAX_VALUE;
            int maxU = Integer.MIN_VALUE;
            int minV = Integer.MAX_VALUE;
            int maxV = Integer.MIN_VALUE;
            for (long cell : piece.cells()) {
                if ((axis == Direction.Axis.X ? BlockPos.getZ(cell) : BlockPos.getX(cell)) != depth) {
                    continue;
                }
                int u = axis == Direction.Axis.X ? BlockPos.getX(cell) : BlockPos.getZ(cell);
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                minV = Math.min(minV, BlockPos.getY(cell));
                maxV = Math.max(maxV, BlockPos.getY(cell));
            }
            // A frame block past the air and the ring past the frame.
            u0 = minU - 2;
            v0 = minV - 2;
            width = maxU - minU + 5;
            height = maxV - minV + 5;
            air = new int[width + 1][height + 1];
            caveAir = new int[width + 1][height + 1];
            obsidian = new int[width + 1][height + 1];
            other = new int[width + 1][height + 1];
            open = new int[width + 1][height + 1];
            fill();
        }

        private BlockState at(int u, int v) {
            return axis == Direction.Axis.X ? world.at(u, v, depth) : world.at(depth, v, u);
        }

        private BlockState beside(int u, int v, int step) {
            return axis == Direction.Axis.X ? world.at(u, v, depth + step) : world.at(depth + step, v, u);
        }

        private void fill() {
            for (int i = 0; i < width; i++) {
                for (int j = 0; j < height; j++) {
                    BlockState state = at(u0 + i, v0 + j);
                    boolean plain = state.is(Blocks.AIR);
                    boolean cave = state.is(Blocks.CAVE_AIR) || state.is(Blocks.VOID_AIR);
                    boolean frame = state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN);
                    boolean opened = plain && (beside(u0 + i, v0 + j, 1).is(Blocks.AIR)
                        || beside(u0 + i, v0 + j, -1).is(Blocks.AIR));
                    add(air, i, j, plain);
                    add(caveAir, i, j, cave);
                    add(obsidian, i, j, frame);
                    add(other, i, j, !plain && !cave && !frame);
                    add(open, i, j, opened);
                }
            }
        }

        private void add(int[][] sums, int i, int j, boolean counts) {
            sums[i + 1][j + 1] = sums[i][j + 1] + sums[i + 1][j] - sums[i][j] + (counts ? 1 : 0);
        }

        // Cells of a rectangle given by its lowest corner and its size in plane steps.
        private int count(int[][] sums, int i, int j, int w, int h) {
            return sums[i + w][j + h] - sums[i][j + h] - sums[i + w][j] + sums[i][j];
        }

        private int corners(int[][] sums, int i, int j, int w, int h) {
            return count(sums, i, j, 1, 1) + count(sums, i + w - 1, j, 1, 1)
                + count(sums, i, j + h - 1, 1, 1) + count(sums, i + w - 1, j + h - 1, 1, 1);
        }

        // Frames whose inside is plain air where the portal stood and whose edge is its obsidian
        // or the plain air left where that was mined. Bigger ones win over the ones they overlap.
        private List<Trace> portals() {
            List<int[]> matches = new ArrayList<>();
            for (int w = FRAME_WIDTH; w <= Math.min(rules.largestWidth(), width - 2); w++) {
                for (int h = FRAME_HEIGHT; h <= Math.min(rules.largestHeight(), height - 2); h++) {
                    for (int i = 1; i + w <= width - 1; i++) {
                        for (int j = 1; j + h <= height - 1; j++) {
                            if (fits(i, j, w, h)) {
                                matches.add(new int[] {i, j, w, h});
                            }
                        }
                    }
                }
            }
            matches.sort(Comparator.comparingInt((int[] match) -> match[2] * match[3]).reversed());
            List<int[]> kept = new ArrayList<>();
            List<Trace> traces = new ArrayList<>();
            for (int[] match : matches) {
                BlockPos key = key(match);
                if (key != null && kept.stream().noneMatch(other -> overlaps(match, other))) {
                    kept.add(match);
                    traces.add(new Trace(Kind.PORTAL, key, box(match), match[2] * match[3]));
                }
            }
            return traces;
        }

        // The edge holds only obsidian or the plain air mined frame blocks leave. Rock there is a
        // cave a structure cut and never a frame.
        private boolean fits(int i, int j, int w, int h) {
            boolean skip = rules.cornersOptional();
            int inside = (w - 2) * (h - 2);
            int insideOther = count(other, i + 1, j + 1, w - 2, h - 2);
            int insideBlocks = insideOther + count(obsidian, i + 1, j + 1, w - 2, h - 2);
            int edgeOther = count(other, i, j, w, h) - insideOther - (skip ? corners(other, i, j, w, h) : 0);
            if (count(caveAir, i, j, w, h) - (skip ? corners(caveAir, i, j, w, h) : 0) > 0 || edgeOther > 0
                || insideBlocks > inside * rules.solidShare()) {
                return false;
            }
            int cells = w * h - (skip ? CORNERS : 0);
            if (count(open, i, j, w, h) - (skip ? corners(open, i, j, w, h) : 0) > cells * rules.openShare()) {
                return false;
            }
            return !rules.closedEdges() || count(air, i, j - 1, w, 1) + count(air, i, j + h, w, 1)
                + count(air, i - 1, j, 1, h) + count(air, i + w, j, 1, h) == 0;
        }

        private static boolean overlaps(int[] a, int[] b) {
            return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
        }

        // A frame is kept at its lowest plain air cell. That cell stays plain air whilst the trace
        // lasts. Null for a frame holding none.
        private BlockPos key(int[] match) {
            for (int row = match[1]; row < match[1] + match[3]; row++) {
                for (int column = match[0]; column < match[0] + match[2]; column++) {
                    if (count(air, column, row, 1, 1) == 1) {
                        return cell(u0 + column, v0 + row);
                    }
                }
            }
            return null;
        }

        private AABB box(int[] match) {
            BlockPos low = cell(u0 + match[0], v0 + match[1]);
            BlockPos high = cell(u0 + match[0] + match[2] - 1, v0 + match[1] + match[3] - 1);
            return new AABB(low).minmax(new AABB(high));
        }

        private BlockPos cell(int u, int v) {
            return axis == Direction.Axis.X ? new BlockPos(u, v, depth) : new BlockPos(depth, v, u);
        }
    }
}
