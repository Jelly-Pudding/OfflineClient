package com.jellypudding.offlineclient.path;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.WitherRoseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

// An A star walk over block positions run on a worker thread. The caller starts
// a search and polls for the answer. The game never waits.
public final class PathFinder {

    // Costs are measured in blocks. A plain walk of one block costs one.
    private static final double DIAGONAL = 1.4142135;
    private static final double JUMP = 0.6;
    private static final double FALL = 0.5;
    private static final double CLIMB = 1.2;
    private static final double WATER = 0.6;
    private static final double WEB = 3;
    private static final double SOUL_SAND = 1;
    private static final double BREAK = 5;
    private static final double PLACE = 5;

    // The eight ways round the compass. The four diagonals come last.
    private static final int[] STEP_X = {0, 1, 0, -1, 1, 1, -1, -1};
    private static final int[] STEP_Z = {-1, 0, 1, 0, -1, 1, 1, -1};

    // Searching is heavy. One worker handles every module in turn.
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "OfflineClient PathFinder");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    // Nodes from the player to the goal. Reached is false when the search ran
    // out and these only go as close as it managed.
    public record Result(List<BlockPos> nodes, boolean reached) {
    }

    public record Step(BlockPos to, double cost) {
    }

    private int maxFall = 3;
    private boolean swim = true;
    private boolean breakBlocks;
    private boolean placeBlocks;
    private int budget = 20000;
    private int area = 8;

    private Future<Result> running;

    public PathFinder maxFall(int blocks) {
        this.maxFall = blocks;
        return this;
    }

    public PathFinder swim(boolean allowed) {
        this.swim = allowed;
        return this;
    }

    public PathFinder breakBlocks(boolean allowed) {
        this.breakBlocks = allowed;
        return this;
    }

    public PathFinder placeBlocks(boolean allowed) {
        this.placeBlocks = allowed;
        return this;
    }

    public PathFinder budget(int nodes) {
        this.budget = nodes;
        return this;
    }

    public PathFinder area(int chunks) {
        this.area = chunks;
        return this;
    }

    // The rules a walker checks the live world against on the client thread.
    public Rules liveRules() {
        return new Rules(View.live(), maxFall, swim, breakBlocks, placeBlocks);
    }

    // The block an entity counts as standing in.
    public static BlockPos standingAt(Entity entity) {
        double y = entity.onGround() ? entity.getY() + 0.5 : entity.getY();
        return BlockPos.containing(entity.getX(), y, entity.getZ());
    }

    // Starts a search. False whilst one is already going or there is no world.
    public boolean search(BlockPos start, PathGoal goal) {
        if (running != null || OfflineClient.MC.level == null) {
            return false;
        }
        BlockPos from = start.immutable();
        Rules rules = new Rules(View.capture(from, area), maxFall, swim, breakBlocks, placeBlocks);
        int nodes = budget;
        running = POOL.submit(() -> new Search(rules, goal, from, nodes).run());
        return true;
    }

    public boolean busy() {
        return running != null;
    }

    // The finished path or null whilst the search is still going.
    public Result poll() {
        if (running == null || !running.isDone()) {
            return null;
        }
        Future<Result> finished = running;
        running = null;
        if (finished.isCancelled()) {
            return null;
        }
        try {
            return finished.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            OfflineClient.LOG.debug("Path search failed", e.getCause());
            return null;
        }
    }

    public void cancel() {
        if (running != null) {
            running.cancel(true);
            running = null;
        }
    }

    // A read only window on the world. A captured view holds the chunks. The
    // worker never touches the live chunk map.
    public static final class View {

        private static final BlockState OUTSIDE = Blocks.VOID_AIR.defaultBlockState();
        private static final BlockState AIR = Blocks.AIR.defaultBlockState();

        private final ClientChunkCache source;
        private final LevelChunk[] chunks;
        private final int originX;
        private final int originZ;
        private final int side;
        private final int minY;
        private final int maxY;

        private View(ClientChunkCache source, LevelChunk[] chunks,
                     int originX, int originZ, int side, int minY, int maxY) {
            this.source = source;
            this.chunks = chunks;
            this.originX = originX;
            this.originZ = originZ;
            this.side = side;
            this.minY = minY;
            this.maxY = maxY;
        }

        // Reads straight from the world. Only safe on the client thread.
        public static View live() {
            ClientLevel level = OfflineClient.MC.level;
            return new View(level.getChunkSource(), null, 0, 0, 0,
                level.getMinY(), level.getMaxY());
        }

        public static View capture(BlockPos centre, int radius) {
            ClientLevel level = OfflineClient.MC.level;
            int side = radius * 2 + 1;
            int originX = (centre.getX() >> 4) - radius;
            int originZ = (centre.getZ() >> 4) - radius;
            LevelChunk[] chunks = new LevelChunk[side * side];
            for (int dx = 0; dx < side; dx++) {
                for (int dz = 0; dz < side; dz++) {
                    chunks[dx * side + dz] = level.getChunkSource()
                        .getChunk(originX + dx, originZ + dz, ChunkStatus.FULL, false);
                }
            }
            return new View(null, chunks, originX, originZ, side,
                level.getMinY(), level.getMaxY());
        }

        public BlockState get(BlockPos pos) {
            int y = pos.getY();
            if (y < minY || y > maxY) {
                return OUTSIDE;
            }
            LevelChunk chunk = chunkAt(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                return OUTSIDE;
            }
            LevelChunkSection section = chunk.getSections()[chunk.getSectionIndex(y)];
            if (section == null || section.hasOnlyAir()) {
                return AIR;
            }
            return section.getBlockState(pos.getX() & 15, y & 15, pos.getZ() & 15);
        }

        private LevelChunk chunkAt(int x, int z) {
            if (source != null) {
                return source.getChunk(x, z, ChunkStatus.FULL, false);
            }
            int dx = x - originX;
            int dz = z - originZ;
            if (dx < 0 || dx >= side || dz < 0 || dz >= side) {
                return null;
            }
            return chunks[dx * side + dz];
        }
    }

    // What the player may walk into and what it costs. Shared by the search and
    // by the walker. Both agree on where a step may go.
    public static final class Rules {

        private final View view;
        private final int maxFall;
        private final boolean swim;
        private final boolean breakBlocks;
        private final boolean placeBlocks;

        public Rules(View view, int maxFall, boolean swim,
                     boolean breakBlocks, boolean placeBlocks) {
            this.view = view;
            this.maxFall = maxFall;
            this.swim = swim;
            this.breakBlocks = breakBlocks;
            this.placeBlocks = placeBlocks;
        }

        // True whilst the player could hold this spot.
        public boolean canStand(BlockPos pos) {
            return entryCost(pos, false) >= 0;
        }

        // True whilst a step from one spot to the next is still on offer.
        public boolean canStep(BlockPos from, BlockPos to) {
            List<Step> steps = new ArrayList<>();
            steps(from, steps);
            for (Step step : steps) {
                if (step.to().equals(to)) {
                    return true;
                }
            }
            return false;
        }

        // The first block to dig out of the way of a spot. Null whilst nothing
        // is in the way or whilst breaking is switched off.
        public BlockPos blocking(BlockPos pos) {
            if (!breakBlocks) {
                return null;
            }
            if (spaceCost(pos, false) < 0 && spaceCost(pos, true) >= 0) {
                return pos;
            }
            BlockPos head = pos.above();
            if (spaceCost(head, false) < 0 && spaceCost(head, true) >= 0) {
                return head;
            }
            return null;
        }

        // Every spot reachable from here in one move.
        public void steps(BlockPos from, List<Step> out) {
            for (int i = 0; i < STEP_X.length; i++) {
                int dx = STEP_X[i];
                int dz = STEP_Z[i];
                boolean diagonal = dx != 0 && dz != 0;
                if (diagonal && !cornerClear(from, dx, dz)) {
                    continue;
                }
                double base = diagonal ? DIAGONAL : 1;
                BlockPos side = from.offset(dx, 0, dz);
                double level = entryCost(side, placeBlocks);
                if (level >= 0) {
                    out.add(new Step(side, base + level));
                }
                if (!diagonal) {
                    addJump(from, side, base, out);
                }
                addDrop(side, base, out);
            }
            addClimb(from, out);
        }

        // A hop onto the block above the neighbour. The head has to clear first.
        private void addJump(BlockPos from, BlockPos side, double base, List<Step> out) {
            if (spaceCost(from.above(2), false) < 0) {
                return;
            }
            double cost = entryCost(side.above(), placeBlocks);
            if (cost >= 0) {
                out.add(new Step(side.above(), base + cost + JUMP));
            }
        }

        // Walks off an edge. The first spot with a floor inside the fall limit
        // is where the player lands.
        private void addDrop(BlockPos side, double base, List<Step> out) {
            if (maxFall <= 0 || spaceCost(side, breakBlocks) < 0
                || spaceCost(side.above(), breakBlocks) < 0) {
                return;
            }
            for (int drop = 1; drop <= maxFall; drop++) {
                BlockPos landing = side.below(drop);
                double cost = entryCost(landing, false);
                if (cost >= 0) {
                    out.add(new Step(landing, base + cost + FALL * drop));
                    return;
                }
                if (spaceCost(landing, false) < 0) {
                    return;
                }
            }
        }

        // Ladders and vines and swimming let the player move straight up or down.
        private void addClimb(BlockPos from, List<Step> out) {
            BlockPos up = from.above();
            BlockPos down = from.below();
            if (climbable(view.get(from))) {
                if (spaceCost(up, false) >= 0 && spaceCost(up.above(), false) >= 0) {
                    out.add(new Step(up, CLIMB));
                }
            } else if (canFloat(view.get(up)) && entryCost(up, false) >= 0) {
                out.add(new Step(up, CLIMB));
            }
            if (canFloat(view.get(down)) && entryCost(down, false) >= 0) {
                out.add(new Step(down, CLIMB));
            }
        }

        private boolean canFloat(BlockState state) {
            return climbable(state) || (swim && state.getFluidState().is(FluidTags.WATER));
        }

        // A diagonal is only allowed whilst both of the sides beside it are open
        // or the player would clip the corner.
        private boolean cornerClear(BlockPos from, int dx, int dz) {
            return fitsAt(from.offset(dx, 0, 0)) && fitsAt(from.offset(0, 0, dz));
        }

        // True whilst the player would fit in the spot whatever is under it.
        public boolean fitsAt(BlockPos pos) {
            return spaceCost(pos, false) >= 0 && spaceCost(pos.above(), false) >= 0;
        }

        // Minus one whilst the player cannot hold the spot. Otherwise the extra
        // cost of getting into it which is nought on plain ground.
        private double entryCost(BlockPos pos, boolean bridge) {
            double feet = spaceCost(pos, breakBlocks);
            if (feet < 0) {
                return -1;
            }
            double head = spaceCost(pos.above(), breakBlocks);
            if (head < 0) {
                return -1;
            }
            double extra = feet + head;
            if (canFloat(view.get(pos))) {
                return extra;
            }
            BlockState floor = view.get(pos.below());
            if (solidFloor(floor)) {
                return floor.getBlock() == Blocks.SOUL_SAND ? extra + SOUL_SAND : extra;
            }
            if (!bridge || unknown(floor) || !floor.canBeReplaced()) {
                return -1;
            }
            return extra + PLACE;
        }

        // Minus one whilst the player cannot move through the block.
        private double spaceCost(BlockPos pos, boolean mine) {
            BlockState state = view.get(pos);
            if (unknown(state) || harmful(state)) {
                return -1;
            }
            if (BlockUtil.blocksMotion(state) && !climbable(state)) {
                if (!mine || state.getBlock().defaultDestroyTime() < 0) {
                    return -1;
                }
                return BREAK;
            }
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                return swim && fluid.is(FluidTags.WATER) ? WATER : -1;
            }
            return state.getBlock() instanceof WebBlock ? WEB : 0;
        }
    }

    public static boolean climbable(BlockState state) {
        return state.is(BlockTags.CLIMBABLE);
    }

    private static boolean unknown(BlockState state) {
        return state.getBlock() == Blocks.VOID_AIR;
    }

    // A fence or a wall stands a block and a half high. Nothing hops onto it.
    private static boolean solidFloor(BlockState state) {
        if (unknown(state) || harmful(state) || climbable(state)) {
            return false;
        }
        Block block = state.getBlock();
        if (block instanceof FenceBlock || block instanceof WallBlock
            || block instanceof FenceGateBlock) {
            return false;
        }
        return BlockUtil.blocksMotion(state);
    }

    private static boolean harmful(BlockState state) {
        Block block = state.getBlock();
        return block instanceof BaseFireBlock
            || block instanceof MagmaBlock
            || block instanceof CactusBlock
            || block instanceof SweetBerryBushBlock
            || block instanceof PowderSnowBlock
            || block instanceof CampfireBlock
            || block instanceof WitherRoseBlock;
    }

    private record Node(long key, double score) {
    }

    // One run of the search. Lives only on the worker thread.
    private static final class Search {

        // How often the worker looks to see whether it has been called off.
        private static final int CANCEL_CHECK = 512;

        private final Rules rules;
        private final PathGoal goal;
        private final BlockPos start;
        private final int budget;

        private final Map<Long, Double> cost = new HashMap<>();
        private final Map<Long, Long> previous = new HashMap<>();
        private final Set<Long> settled = new HashSet<>();
        private final PriorityQueue<Node> open =
            new PriorityQueue<>(Comparator.comparingDouble(Node::score));

        private Search(Rules rules, PathGoal goal, BlockPos start, int budget) {
            this.rules = rules;
            this.goal = goal;
            this.start = start;
            this.budget = budget;
        }

        private Result run() {
            cost.put(start.asLong(), 0d);
            open.add(new Node(start.asLong(), goal.heuristic(start)));
            BlockPos closest = start;
            double closestScore = goal.heuristic(start);
            List<Step> steps = new ArrayList<>();

            for (int expanded = 0; expanded < budget && !open.isEmpty(); expanded++) {
                if (expanded % CANCEL_CHECK == 0 && Thread.currentThread().isInterrupted()) {
                    return new Result(List.of(), false);
                }
                long key = open.poll().key();
                if (!settled.add(key)) {
                    continue;
                }
                BlockPos pos = BlockPos.of(key);
                if (goal.reached(pos)) {
                    return new Result(trace(pos), true);
                }
                double here = goal.heuristic(pos);
                if (here < closestScore) {
                    closestScore = here;
                    closest = pos;
                }
                steps.clear();
                rules.steps(pos, steps);
                relax(key, steps);
            }
            return new Result(trace(closest), false);
        }

        private void relax(long from, List<Step> steps) {
            double base = cost.get(from);
            for (Step step : steps) {
                long key = step.to().asLong();
                if (settled.contains(key)) {
                    continue;
                }
                double total = base + step.cost();
                Double known = cost.get(key);
                if (known != null && known <= total) {
                    continue;
                }
                cost.put(key, total);
                previous.put(key, from);
                open.add(new Node(key, total + goal.heuristic(step.to())));
            }
        }

        private List<BlockPos> trace(BlockPos end) {
            List<BlockPos> nodes = new ArrayList<>();
            Long key = end.asLong();
            while (key != null) {
                nodes.add(BlockPos.of(key));
                key = previous.get(key);
            }
            Collections.reverse(nodes);
            return List.copyOf(nodes);
        }
    }
}
