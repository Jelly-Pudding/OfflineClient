package com.jellypudding.offlineclient.path;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.PathfinderModule;
import com.jellypudding.offlineclient.util.Background;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChunkWindow;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
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
import java.util.concurrent.Future;

// An A star walk over block positions run on a worker thread. The caller starts
// a search and polls for the answer. The game never waits.
public final class PathFinder {

    // Costs are measured in blocks. A plain walk of one block costs one.
    private static final double DIAGONAL = 1.4142135;
    private static final double JUMP = 0.6;
    static final double FALL = 0.5;
    private static final double CLIMB = 1.2;
    private static final double WATER = 0.6;
    private static final double WEB = 3;
    private static final double SOUL_SAND = 1;
    private static final double BREAK = 5;
    private static final double PLACE = 3;

    // A drop that stands for any drop at all. No world is that deep.
    private static final int ANY_FALL = 512;

    // A step height a hair short of a whole block still climbs one.
    private static final double STEP_SLACK = 0.01;

    // The nodes a search may open before it settles for the closest it got. Then the
    // radius in chunks it may search around the start.
    private static final int NODE_BUDGET = 20000;
    private static final int AREA_CHUNKS = 8;

    // The eight ways round the compass. The four diagonals come last.
    private static final int[] STEP_X = {0, 1, 0, -1, 1, 1, -1, -1};
    private static final int[] STEP_Z = {-1, 0, 1, 0, -1, 1, 1, -1};

    // Searching is heavy. One worker handles every module in turn.
    private static final ExecutorService POOL = Background.worker("OfflineClient PathFinder");

    // Nodes from the player to the goal. A search that ran out goes only as close as it managed.
    public record Result(List<BlockPos> nodes) {
    }

    record Step(BlockPos to, double cost) {
    }

    // What a walk may count on. The drop it may take and the drop that lands unhurt. The liquids
    // Jesus holds the player up on and the blocks Step lifts them and whether the hotbar may lay a floor.
    record Means(int maxFall, int safeFall, boolean swim, boolean breakBlocks, boolean onWater, boolean onLava,
                 int stepUp, boolean bridge, boolean pillar) {
    }

    // A caller that set the drop itself keeps it whatever NoFall would allow.
    private int maxFall;
    private boolean fallChosen;
    private boolean swim = true;
    private boolean breakBlocks;

    private Future<Result> running;

    public PathFinder maxFall(int blocks) {
        this.maxFall = blocks;
        fallChosen = true;
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

    // The rules a walker checks the live world against on the client thread.
    public Rules liveRules() {
        return new Rules(ChunkWindow.live(), means());
    }

    // Read on the client thread as a search starts and on every tick of a walk. A module
    // switched on or off part way changes the walk from the next tick.
    private Means means() {
        LocalPlayer player = OfflineClient.MC.player;
        boolean onWater = PathWalker.unsneaked(() -> Modules.walksOn(Blocks.WATER.defaultBlockState()));
        boolean onLava = PathWalker.unsneaked(() -> Modules.walksOn(Blocks.LAVA.defaultBlockState()));
        int safeFall = (int) DamageUtil.safeFall(player);
        int fall = fallChosen ? maxFall : Modules.fallsFree() ? ANY_FALL : safeFall;
        int stepUp = player == null ? 0 : (int) (player.maxUpStep() + STEP_SLACK);
        boolean bridge = PathfinderModule.bridges();
        boolean pillar = PathfinderModule.pillars();
        // The hotbar is only looked through for a walk that may lay blocks at all.
        boolean lays = (bridge || pillar) && player != null && PathWalker.blockSlot(player.blockPosition()) != -1;
        return new Means(fall, safeFall, swim && !onWater, breakBlocks, onWater, onLava, stepUp, lays && bridge,
            lays && pillar);
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
        Rules rules = new Rules(ChunkWindow.capture(from, AREA_CHUNKS), means());
        running = POOL.submit(() -> new Search(rules, goal, from, NODE_BUDGET).run());
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

    // What the player may walk into and what it costs. Shared by the search and
    // by the walker. Both agree on where a step may go.
    public static final class Rules {

        private final ChunkWindow view;
        private final Means means;

        Rules(ChunkWindow view, Means means) {
            this.view = view;
            this.means = means;
        }

        boolean canStand(BlockPos pos) {
            return entryCost(pos) >= 0;
        }

        // True when a slip into the spot ends in a fall that hurts. NoFall takes the hurt
        // out of any fall that has somewhere to land.
        boolean deepDrop(BlockPos pos) {
            return landingBelow(pos, means.maxFall() == ANY_FALL ? ANY_FALL : means.safeFall()) == null;
        }

        // True where the floor of a spot is a liquid Jesus holds the player up on.
        boolean afloat(BlockPos pos) {
            return holdsUp(view.get(pos.below()));
        }

        // Blocks the player walks up without a jump.
        int stepUp() {
            return means.stepUp();
        }

        // The floor a walk has to lay before the player can stand in the spot. Null when the
        // spot has one or the walk lays none.
        BlockPos missingFloor(BlockPos pos) {
            return (means.bridge() || means.pillar()) && wantsFloor(pos) ? pos.below() : null;
        }

        // Room to stand with nothing under it but a block from the hotbar can go there.
        private boolean wantsFloor(BlockPos pos) {
            BlockState floor = view.get(pos.below());
            return fitsAt(pos) && !canFloat(view.get(pos)) && !unknown(floor) && floor.canBeReplaced()
                && !holdsUp(floor);
        }

        boolean canStep(BlockPos from, BlockPos to) {
            List<Step> steps = new ArrayList<>();
            steps(from, steps);
            for (Step step : steps) {
                if (step.to().equals(to)) {
                    return true;
                }
            }
            return false;
        }

        // The first block to dig out of the way of the step from prev to the node. Null when
        // nothing is in the way or breaking is switched off.
        BlockPos blocking(BlockPos prev, BlockPos node) {
            if (!means.breakBlocks()) {
                return null;
            }
            // A drop passes the side column from head height down to the landing. The face the
            // player sees goes first.
            if (node.getY() < prev.getY() && (node.getX() != prev.getX() || node.getZ() != prev.getZ())) {
                for (int y = prev.getY() + 1; y >= node.getY(); y--) {
                    BlockPos pos = node.atY(y);
                    if (mustDig(pos)) {
                        return pos;
                    }
                }
                return null;
            }
            if (mustDig(node)) {
                return node;
            }
            BlockPos head = node.above();
            return mustDig(head) ? head : null;
        }

        // True for a block that stops the player and may be broken.
        private boolean mustDig(BlockPos pos) {
            return spaceCost(pos, false) < 0 && spaceCost(pos, true) >= 0;
        }

        // Every spot reachable from here in one move.
        void steps(BlockPos from, List<Step> out) {
            for (int i = 0; i < STEP_X.length; i++) {
                int dx = STEP_X[i];
                int dz = STEP_Z[i];
                boolean diagonal = dx != 0 && dz != 0;
                if (diagonal && !cornerClear(from, dx, dz)) {
                    continue;
                }
                double base = diagonal ? DIAGONAL : 1;
                BlockPos side = from.offset(dx, 0, dz);
                double level = entryCost(side);
                if (level >= 0) {
                    out.add(new Step(side, base + level));
                } else if (!diagonal && means.bridge() && !afloat(from) && wantsFloor(side)) {
                    out.add(new Step(side, base + PLACE));
                }
                if (!diagonal) {
                    addRise(from, side, base, out);
                }
                addDrop(side, base, out);
            }
            addClimb(from, out);
        }

        // A hop onto the block above the neighbour or a walk up as many blocks as the
        // player steps without one. The head has to clear all the way first.
        private void addRise(BlockPos from, BlockPos side, double base, List<Step> out) {
            for (int rise = 1; rise <= Math.max(1, means.stepUp()); rise++) {
                if (spaceCost(from.above(rise + 1), false) < 0) {
                    return;
                }
                double cost = entryCost(side.above(rise));
                if (cost >= 0) {
                    out.add(new Step(side.above(rise), base + cost + (rise > means.stepUp() ? JUMP : 0)));
                    return;
                }
            }
        }

        // Walks off an edge and lands inside the fall limit. The drop pays for the two blocks of the
        // side column the player walks into and the landing for itself and the spot above it.
        private void addDrop(BlockPos side, double base, List<Step> out) {
            if (means.maxFall() <= 0) {
                return;
            }
            double feet = spaceCost(side, means.breakBlocks());
            if (feet < 0) {
                return;
            }
            double head = spaceCost(side.above(), means.breakBlocks());
            if (head < 0) {
                return;
            }
            BlockPos landing = landingBelow(side, means.maxFall());
            if (landing == null) {
                return;
            }
            int drop = side.getY() - landing.getY();
            // After a drop of one the spot above the landing is the side and the landing pays for it.
            double passed = drop > 1 ? head + feet : head;
            out.add(new Step(landing, base + passed + entryCost(landing) + FALL * drop));
        }

        // The first spot with a floor that a player who drops into the spot comes down to.
        // Null when a block or the limit stops the fall before one.
        private BlockPos landingBelow(BlockPos side, int limit) {
            for (int drop = 1; drop <= limit; drop++) {
                BlockPos landing = side.below(drop);
                if (entryCost(landing) >= 0) {
                    return landing;
                }
                if (spaceCost(landing, false) < 0) {
                    return null;
                }
            }
            return null;
        }

        // Ladders and vines and swimming let the player move straight up or down.
        private void addClimb(BlockPos from, List<Step> out) {
            BlockPos up = from.above();
            BlockPos down = from.below();
            if (climbable(view.get(from))) {
                if (spaceCost(up, false) >= 0 && spaceCost(up.above(), false) >= 0) {
                    out.add(new Step(up, CLIMB));
                }
            } else if (canFloat(view.get(up)) && entryCost(up) >= 0) {
                out.add(new Step(up, CLIMB));
            } else if (means.pillar() && !afloat(from) && wantsFloor(up)) {
                out.add(new Step(up, CLIMB + PLACE));
            }
            if (canFloat(view.get(down)) && entryCost(down) >= 0) {
                out.add(new Step(down, CLIMB));
            }
        }

        private boolean canFloat(BlockState state) {
            return climbable(state) || (means.swim() && state.getFluidState().is(FluidTags.WATER));
        }

        // A diagonal needs both of the sides beside it open or the player would clip
        // the corner.
        private boolean cornerClear(BlockPos from, int dx, int dz) {
            return fitsAt(from.offset(dx, 0, 0)) && fitsAt(from.offset(0, 0, dz));
        }

        // True when the player would fit in the spot whatever is under it.
        boolean fitsAt(BlockPos pos) {
            return spaceCost(pos, false) >= 0 && spaceCost(pos.above(), false) >= 0;
        }

        // Minus one when the player cannot stand in the spot. Otherwise the extra
        // cost of getting into it which is nought on plain ground.
        private double entryCost(BlockPos pos) {
            double feet = spaceCost(pos, means.breakBlocks());
            if (feet < 0) {
                return -1;
            }
            double head = spaceCost(pos.above(), means.breakBlocks());
            if (head < 0) {
                return -1;
            }
            double extra = feet + head;
            if (canFloat(view.get(pos))) {
                return extra;
            }
            BlockState floor = view.get(pos.below());
            if (!solidFloor(floor) && !holdsUp(floor)) {
                return -1;
            }
            return floor.getBlock() == Blocks.SOUL_SAND ? extra + SOUL_SAND : extra;
        }

        // A liquid Jesus makes a floor of.
        private boolean holdsUp(BlockState floor) {
            FluidState fluid = floor.getFluidState();
            return means.onWater() && fluid.is(FluidTags.WATER) || means.onLava() && fluid.is(FluidTags.LAVA);
        }

        // Minus one when the player cannot move through the block.
        private double spaceCost(BlockPos pos, boolean mine) {
            BlockState state = view.get(pos);
            if (unknown(state) || BlockUtil.harmful(state)) {
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
                return means.swim() && fluid.is(FluidTags.WATER) ? WATER : -1;
            }
            return state.getBlock() instanceof WebBlock ? WEB : 0;
        }
    }

    private static boolean climbable(BlockState state) {
        return state.is(BlockTags.CLIMBABLE);
    }

    private static boolean unknown(BlockState state) {
        return state.getBlock() == Blocks.VOID_AIR;
    }

    // A fence or a wall stands a block and a half high. Nothing hops onto it.
    private static boolean solidFloor(BlockState state) {
        if (unknown(state) || BlockUtil.harmful(state) || climbable(state)) {
            return false;
        }
        Block block = state.getBlock();
        if (block instanceof FenceBlock || block instanceof WallBlock
            || block instanceof FenceGateBlock) {
            return false;
        }
        return BlockUtil.blocksMotion(state);
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
            open.add(new Node(start.asLong(), heuristic(start)));
            BlockPos closest = start;
            double closestScore = heuristic(start);
            List<Step> steps = new ArrayList<>();

            for (int expanded = 0; expanded < budget && !open.isEmpty(); expanded++) {
                if (expanded % CANCEL_CHECK == 0 && Thread.currentThread().isInterrupted()) {
                    return new Result(List.of());
                }
                long key = open.poll().key();
                if (!settled.add(key)) {
                    continue;
                }
                BlockPos pos = BlockPos.of(key);
                if (goal.reached(pos)) {
                    return new Result(trace(pos));
                }
                double here = heuristic(pos);
                if (here < closestScore) {
                    closestScore = here;
                    closest = pos;
                }
                steps.clear();
                rules.steps(pos, steps);
                relax(key, steps);
            }
            return new Result(trace(closest));
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
                open.add(new Node(key, total + heuristic(step.to())));
            }
        }

        private double heuristic(BlockPos pos) {
            return goal.heuristic(pos, rules.stepUp());
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
