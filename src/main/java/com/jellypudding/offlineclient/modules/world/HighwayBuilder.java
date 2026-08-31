package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.AxisWalker;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Builds a highway along one line at the height it starts from. Every tick
 * the stretch around the player is checked and the nearest job in reach is
 * done. Clearing comes first and then the floor and the walls and the
 * ceiling. Blocks with nothing to lean on are placed straight into the air
 * the way AirPlace does and a wall or a floor can start anywhere. The line
 * may run diagonally in which case the shell is picked by distance from it.
 */
public final class HighwayBuilder extends Module {

    private static final int MINE_COLOR = 0xFFFF5030;
    private static final int PLACE_COLOR = 0xFF50FF80;

    // Finished depths that must lie ahead before the module walks on.
    private static final int LEAD = 2;

    // How far ahead of the player the work reaches and how far behind it is checked.
    private static final int AHEAD = 6;
    private static final int BEHIND = 2;

    // Ticks of no progress before the module gives up walking on its own.
    private static final int IDLE_LIMIT = 200;

    // A walking pace in blocks per tick whilst the view is left free.
    private static final double WALK_SPEED = 0.13;

    public enum Movement { AUTO, MANUAL }

    private final NumberSetting width = new NumberSetting("Width",
        "How wide the highway is.", 4, 1, 5, 1, " blocks").min(1).max(9);
    private final NumberSetting height = new NumberSetting("Height",
        "How tall the tunnel is. Walls are built to the same height.", 3, 2, 5, 1, " blocks").min(2).max(9);
    private final BoolSetting diagonal = new BoolSetting("Diagonal",
        "Lets the line run at forty five degrees when you face that way.", false);
    private final EnumSetting<Movement> movement = new EnumSetting<>("Movement",
        "Who walks.", Movement.AUTO)
        .describe(Movement.AUTO, "The module walks on once the stretch ahead is finished.")
        .describe(Movement.MANUAL, "You walk. The module builds around wherever you are along the line.");
    private final BoolSetting freeLook = new BoolSetting("Free look",
        "Leaves your view alone. The walking and building follow the line on their own.", false)
        .under(movement, Movement.AUTO);
    private final BoolSetting skipUnreachable = new BoolSetting("Skip unreachable",
        "Walks on past blocks you cannot reach from the line instead of waiting for them.", true)
        .under(movement, Movement.AUTO);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks allowed in the build. The one in your hand wins and otherwise the first in the hotbar.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.OBSIDIAN, Blocks.NETHERRACK, Blocks.BLACKSTONE, Blocks.BASALT,
            Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE));
    private final BoolSetting floor = new BoolSetting("Floor",
        "Fills the floor under the highway.", true);
    private final BoolSetting replaceFloor = new BoolSetting("Replace floor",
        "Digs out floor blocks that are not on the list and paves over them.", false)
        .under(floor);
    private final BoolSetting walls = new BoolSetting("Walls",
        "Builds a wall down each side of the highway.", false);
    private final BoolSetting replaceWalls = new BoolSetting("Replace walls",
        "Digs out wall blocks that are not on the list and rebuilds them.", false)
        .under(walls);
    private final BoolSetting ceiling = new BoolSetting("Ceiling",
        "Roofs the highway over.", false);
    private final BoolSetting replaceCeiling = new BoolSetting("Replace ceiling",
        "Digs out ceiling blocks that are not on the list and roofs over them.", false)
        .under(ceiling);
    private final BoolSetting torches = new BoolSetting("Torches",
        "Puts a torch on the left edge as you go. Needs torches in your hotbar.", false);
    private final NumberSetting torchSpacing = new NumberSetting("Torch spacing",
        "Blocks between one torch and the next.", 8, 2, 16, 1, " blocks").min(1)
        .under(torches);
    private final NumberSetting torchHeight = new NumberSetting("Torch height",
        "Blocks of air under each torch. Zero stands it on the floor and more hangs it on the wall. Never above the tunnel.",
        0, 0, 4, 1, " blocks").min(0).max(8).under(torches);
    private final BoolSetting stopWhenEmpty = new BoolSetting("Stop when empty",
        "Turns the module off once you run out of blocks.", true);
    private final BoolSetting outline = new BoolSetting("Outline",
        "Draws the stretch being worked on.", true);
    private final ColorSetting outlineColor = new ColorSetting("Outline colour",
        "Colour of the outline.", 200, false).under(outline);

    private final AxisWalker walker = new AxisWalker();
    private final SlotSwap slots = new SlotSwap();

    private int idleTicks;
    private double bestTravelled;
    private boolean walking;

    private BlockPos mineTarget;
    private BlockPos placeTarget;

    // Every block of the shell around the player this tick.
    private final List<BlockPos> stretch = new ArrayList<>();

    public HighwayBuilder() {
        super("HighwayBuilder", "Digs and builds a highway along one line at a fixed height.", Category.WORLD);
        addSettings(width, height, diagonal, movement, freeLook, skipUnreachable, blocks, floor,
            replaceFloor, walls, replaceWalls, ceiling, replaceCeiling, torches, torchSpacing,
            torchHeight, stopWhenEmpty, outline, outlineColor);
        searchTags("highway", "nether", "tunnel", "road");
    }

    @Override
    public String getSuffix() {
        if (!walker.isLocked()) {
            return null;
        }
        return walker.heading() + " " + (int) Math.max(0, bestTravelled);
    }

    // Bringing this back at launch would start digging at once.
    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        walker.clear();
        slots.forget();
        mineTarget = null;
        placeTarget = null;
        if (inGame()) {
            lockAxis();
        }
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        slots.restoreIfMine();
        stopWalking();
        walker.clear();
        mineTarget = null;
        placeTarget = null;
        stretch.clear();
    }

    private void lockAxis() {
        walker.lock(diagonal.isOn());
        idleTicks = 0;
        bestTravelled = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        mineTarget = null;
        placeTarget = null;
        if (!inGame()) {
            stopWalking();
            return;
        }
        if (!walker.isLocked()) {
            lockAxis();
        }
        if (!safeToWork()) {
            return;
        }
        boolean auto = movement.is(Movement.AUTO);
        // Manual hands the walking back rather than leaving the key held.
        if (!auto) {
            stopWalking();
        }
        walker.holdAxis(auto && !freeLook.isOn());
        trackProgress();

        double here = walker.travelled();
        gatherStretch(here - BEHIND, here + AHEAD);
        if (work()) {
            if (auto) {
                stopWalking();
            }
            return;
        }
        // Running out of blocks turns the module off inside a fill.
        if (!isEnabled()) {
            return;
        }
        if (auto) {
            walk(here);
        }
    }

    private boolean safeToWork() {
        if (mc.player.isSpectator() || mc.player.isPassenger()) {
            stop("HighwayBuilder stopped because you cannot build from there.");
            return false;
        }
        if (Math.abs(mc.player.getY() - walker.floorY()) > 1.5) {
            stop("HighwayBuilder stopped because you left the highway floor.");
            return false;
        }
        if (movement.is(Movement.AUTO) && idleTicks > IDLE_LIMIT) {
            stop("HighwayBuilder stopped because it could not get any further.");
            return false;
        }
        return true;
    }

    private void trackProgress() {
        double travelled = walker.travelled();
        if (travelled > bestTravelled + 0.05) {
            bestTravelled = travelled;
            idleTicks = 0;
        } else {
            idleTicks++;
        }
    }

    /**
     * Collects every block of the tunnel and its shell between two depths
     * along the line. The box around the player is sieved by distance along
     * and across the line. A diagonal works the same as a straight run.
     */
    private void gatherStretch(double from, double to) {
        stretch.clear();
        int reach = AHEAD + width.getInt() + 2;
        BlockPos centre = mc.player.blockPosition();
        int tall = height.getInt();
        double nearEdge = leftLane() - 1.5;
        double farEdge = rightLane() + 1.5;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                double along = walker.alongOf(x + 0.5, z + 0.5);
                if (along < from || along > to) {
                    continue;
                }
                double across = walker.acrossOf(x + 0.5, z + 0.5);
                if (across < nearEdge || across > farEdge) {
                    continue;
                }
                for (int up = -1; up <= tall; up++) {
                    stretch.add(new BlockPos(x, walker.floorY() + up, z));
                }
            }
        }
    }

    /**
     * Does the nearest job across the stretch. Clearing wins over filling.
     * Nothing is built into a block that still has to come out. True when
     * the tick was spent on something.
     */
    private boolean work() {
        BlockPos dig = nearest(this::needsClearing);
        if (dig != null) {
            mineTarget = dig;
            idleTicks = 0;
            slots.restoreIfMine();
            if (!BlockMiner.mine(dig, true)) {
                mineTarget = null;
                return false;
            }
            return true;
        }
        BlockMiner.release();
        BlockPos fill = nearest(this::needsFilling);
        if (fill != null) {
            placeTarget = fill;
            return place(fill);
        }
        BlockPos torch = nearest(this::needsTorch);
        if (torch != null) {
            return placeTorch(torch);
        }
        slots.restoreIfMine();
        return false;
    }

    // The closest block of the stretch in reach that passes the test.
    private BlockPos nearest(Predicate<BlockPos> wanted) {
        double reach = mc.player.blockInteractionRange();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : stretch) {
            if (!wanted.test(pos)) {
                continue;
            }
            double distance = BlockUtil.distanceTo(pos);
            if (distance <= reach && distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    private enum Part { TUNNEL, FLOOR, WALL, CEILING, OUTSIDE }

    // Which part of the highway a block belongs to by where its middle falls.
    private Part partOf(BlockPos pos) {
        int up = pos.getY() - walker.floorY();
        double across = walker.acrossOf(pos);
        boolean inside = across >= leftLane() - 0.5 && across <= rightLane() + 0.5;
        boolean beside = !inside && across >= leftLane() - 1.5 && across <= rightLane() + 1.5;
        int tall = height.getInt();
        if (inside && up >= 0 && up < tall) {
            return Part.TUNNEL;
        }
        if (inside && up == -1) {
            return Part.FLOOR;
        }
        if (beside && up >= 0 && up < tall) {
            return Part.WALL;
        }
        if (up == tall && (inside || (beside && walls.isOn()))) {
            return Part.CEILING;
        }
        // The blocks the walls stand on are part of the floor once walls are wanted.
        if (beside && up == -1 && walls.isOn()) {
            return Part.FLOOR;
        }
        return Part.OUTSIDE;
    }

    // Anything in the tunnel. A shell block that is not on the list when that shell is being replaced.
    private boolean needsClearing(BlockPos pos) {
        if (!BlockUtil.diggable(pos) || BlockUtil.isStandingOn(pos)) {
            return false;
        }
        Part part = partOf(pos);
        if (part == Part.TUNNEL) {
            // Torches light the way and never block it.
            return !isTorch(BlockUtil.state(pos).getBlock());
        }
        if (allowed(BlockUtil.state(pos).getBlock())) {
            return false;
        }
        return switch (part) {
            case FLOOR -> floor.isOn() && replaceFloor.isOn();
            case WALL -> walls.isOn() && replaceWalls.isOn();
            case CEILING -> ceiling.isOn() && replaceCeiling.isOn();
            default -> false;
        };
    }

    private boolean needsFilling(BlockPos pos) {
        if (!BlockUtil.isReplaceable(pos) || BlockUtil.intersectsPlayer(pos)) {
            return false;
        }
        return switch (partOf(pos)) {
            case FLOOR -> floor.isOn();
            case WALL -> walls.isOn();
            case CEILING -> ceiling.isOn();
            default -> false;
        };
    }

    // A block goes against a neighbour when there is one and into the air when there is not.
    private boolean place(BlockPos target) {
        int slot = BlockUtil.findBlockSlot(this::allowed);
        if (slot == -1) {
            if (stopWhenEmpty.isOn()) {
                stop("HighwayBuilder stopped because you ran out of blocks.");
            }
            return false;
        }
        slots.select(slot);
        boolean placed = BlockUtil.placeAny(target, true, true);
        if (placed) {
            idleTicks = 0;
        }
        return placed;
    }

    // The torch never sits above the top of the tunnel.
    private int torchUp() {
        return Math.min(torchHeight.getInt(), height.getInt() - 1);
    }

    /**
     * The torch spots sit on the left edge every few blocks at the chosen
     * height. On the floor a torch stands on the block below. Higher up it
     * hangs on the wall beside it.
     */
    private boolean needsTorch(BlockPos pos) {
        if (!torches.isOn() || pos.getY() - walker.floorY() != torchUp()) {
            return false;
        }
        double across = walker.acrossOf(pos);
        if (across < leftLane() - 0.5 || across >= leftLane() + 0.5) {
            return false;
        }
        int depth = walker.depthOf(pos);
        if (depth <= 0 || depth % torchSpacing.getInt() != 0) {
            return false;
        }
        return BlockUtil.isReplaceable(pos) && !BlockUtil.intersectsPlayer(pos) && torchSupport(pos) != null;
    }

    private Direction torchSupport(BlockPos pos) {
        if (BlockUtil.isSolid(pos.below())) {
            return Direction.DOWN;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos beside = pos.relative(side);
            if (BlockUtil.isSolid(beside) && partOf(beside) == Part.WALL) {
                return side;
            }
        }
        return null;
    }

    private boolean placeTorch(BlockPos pos) {
        int slot = BlockUtil.findBlockSlot(block -> block == Blocks.TORCH || block == Blocks.SOUL_TORCH);
        if (slot == -1) {
            return false;
        }
        Direction support = torchSupport(pos);
        if (support == null) {
            return false;
        }
        slots.select(slot);
        boolean placed = BlockUtil.place(pos, support, true, true);
        slots.restoreIfMine();
        return placed;
    }

    // Walks once the stretch just ahead is finished or only holds work out of reach.
    private void walk(double here) {
        slots.restoreIfMine();
        boolean go = stretchDone(here + 1, here + LEAD);
        if (freeLook.isOn()) {
            stopWalking();
            if (go) {
                walker.walkAlong(WALK_SPEED);
            }
            return;
        }
        walking = go;
        if (go) {
            mc.options.keyUp.setDown(true);
        } else {
            InputUtil.release(mc.options.keyUp);
        }
    }

    private boolean stretchDone(double from, double to) {
        double reach = mc.player.blockInteractionRange();
        for (BlockPos pos : stretch) {
            double along = walker.alongOf(pos);
            if (along < from || along > to) {
                continue;
            }
            if (!needsClearing(pos) && !needsFilling(pos)) {
                continue;
            }
            if (!skipUnreachable.isOn() || BlockUtil.distanceTo(pos) <= reach) {
                return false;
            }
        }
        return true;
    }

    private void stopWalking() {
        if (walking) {
            walking = false;
            InputUtil.release(mc.options.keyUp);
        }
    }

    // An empty list means any plain building block will do.
    private boolean allowed(Block block) {
        if (blocks.size() == 0) {
            return BlockUtil.isBuildingBlock(block, BlockPos.ZERO);
        }
        return blocks.contains(block);
    }

    private static boolean isTorch(Block block) {
        return block == Blocks.TORCH || block == Blocks.WALL_TORCH
            || block == Blocks.SOUL_TORCH || block == Blocks.SOUL_WALL_TORCH;
    }

    private int leftLane() {
        return -((width.getInt() - 1) / 2);
    }

    private int rightLane() {
        return width.getInt() - 1 + leftLane();
    }

    private void stop(String reason) {
        ChatUtil.error(reason);
        setEnabled(false);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!walker.isLocked() || !inGame()) {
            return;
        }
        if (outline.isOn()) {
            drawStretch(event);
        }
        if (mineTarget != null) {
            event.getBatch().outlineBlock(mineTarget, MINE_COLOR, false);
        }
        if (placeTarget != null) {
            event.getBatch().outlineBlock(placeTarget, PLACE_COLOR, false);
        }
    }

    // A frame around the tunnel from just behind the player to the end of the reach.
    private void drawStretch(Render3DEvent event) {
        int color = ColorUtil.withAlpha(outlineColor.getColor(), 255);
        double here = Math.floor(walker.travelled());
        double from = here - BEHIND;
        double to = here + AHEAD + 1;
        double left = leftLane() - 0.5;
        double right = rightLane() + 0.5;
        int tall = height.getInt();
        Vec3[] corners = {
            walker.pointAt(from, left, 0), walker.pointAt(from, right, 0),
            walker.pointAt(to, right, 0), walker.pointAt(to, left, 0)
        };
        for (int i = 0; i < 4; i++) {
            Vec3 a = corners[i];
            Vec3 b = corners[(i + 1) % 4];
            event.getBatch().line(a, b, color, true);
            event.getBatch().line(a.add(0, tall, 0), b.add(0, tall, 0), color, true);
            event.getBatch().line(a, a.add(0, tall, 0), color, true);
        }
    }
}
