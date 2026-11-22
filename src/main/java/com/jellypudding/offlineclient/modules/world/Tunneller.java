package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.AxisWalker;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.PlaceWatch;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.UseBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public final class Tunneller extends Module {

    private static final int TUNNEL_COLOR = 0xFF40C0FF;
    private static final int CURRENT_COLOR = 0xFFFF5030;

    private static final int SCAN_DEPTHS = 8;

    private static final int FALL_WATCH = 16;

    private final NumberSetting width = new NumberSetting("Width",
        "How wide the tunnel is.", 1, 1, 5, 1, " blocks").max(9);
    // Two blocks give you room to walk. Above eight blocks the top row is past the server's reach
    // from the floor.
    private final NumberSetting height = new NumberSetting("Height",
        "How tall the tunnel is.", 2, 2, 5, 1, " blocks").max(8);
    private final NumberSetting length = new NumberSetting("Length",
        "How far to dig before stopping.", 64, 8, 512, 8, " blocks").min(1);
    private final BoolSetting wallLiquids = new BoolSetting("Wall off liquids",
        "Covers water and lava the tunnel breaks into with a block from your hotbar.", true);
    private final BoolSetting fillFloor = new BoolSetting("Fill floor",
        "Fills every hole in the tunnel floor ahead of you with a block from your hotbar. Without it a"
            + " drop into a hole ends the tunnel.", true);
    private final BoolSetting torches = new BoolSetting("Torches",
        "Puts a torch on the floor as you go. Needs torches in your hotbar.", false);
    private final NumberSetting spacing = new NumberSetting("Torch spacing",
        "Blocks between one torch and the next.", 8, 2, 16, 1, " blocks")
        .min(1).under(torches);

    private final AxisWalker walker = new AxisWalker();
    private final PlaceWatch place = new PlaceWatch();
    private final SlotSwap slots = new SlotSwap();
    private int cleared;
    private int lastTorch;
    private BlockPos current;
    private boolean toldLeft;

    public Tunneller() {
        super("Tunneller", "Digs a straight tunnel the way you face.", Category.WORLD);
        addSettings(width, height, length, wallLiquids, fillFloor, torches, spacing);
        searchTags("tunnel", "mine", "dig", "strip mine");
    }

    @Override
    public String getSuffix() {
        if (!walker.isLocked()) {
            return null;
        }
        return Math.min(cleared, length.getInt()) + " of " + length.getInt();
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
        place.clear();
        slots.forget();
        current = null;
        if (inGame()) {
            lockAxis();
        }
    }

    private void lockAxis() {
        walker.lock();
        cleared = 0;
        lastTorch = 0;
        toldLeft = false;
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        slots.restoreIfMine();
        InputUtil.release(mc.options.keyUp);
        walker.clear();
        current = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        current = null;
        // A dead player still ticks and the server ignores every click it makes.
        if (mc.player.isDeadOrDying()) {
            disable("Tunneller stopped because you died.");
            return;
        }
        if (place.movedAway()) {
            disable("Tunneller stopped because you changed world.");
            return;
        }
        if (!walker.isLocked()) {
            lockAxis();
        }
        if (mc.player.isSpectator() || mc.player.isPassenger()) {
            disable("Tunneller stopped because you cannot dig from there.");
            return;
        }
        if (walker.offFloor()) {
            disable("Tunneller stopped because you left the tunnel floor.");
            return;
        }

        // Digging and placing wait whilst something else has the hands. A fight FightBot or Protect
        // walks to keeps the view and the forward key as well.
        if (Hands.taken()) {
            BlockMiner.release();
            if (!Modules.fightsOnFoot()) {
                walker.holdAxis();
                // The key goes up whoever held it. That keeps an AutoWalk switched on before Tunneller from
                // walking you on whilst the tunnel waits. Both tick in the order they were switched on.
                InputUtil.release(mc.options.keyUp);
            }
            return;
        }
        walker.holdAxis();
        if (wallLiquids.isOn() && sealLiquid()) {
            return;
        }
        if (fillFloor.isOn() && fillHole()) {
            return;
        }
        current = nextBlock();

        if (current != null) {
            InputUtil.release(mc.options.keyUp);
            if (!BlockMiner.mine(current, true)) {
                current = null;
            }
            return;
        }
        if (cleared >= length.getInt() && walker.travelled() >= length.getInt() - 1) {
            tell("finished §f" + length.getInt() + "§7 blocks.");
            setEnabled(false);
            return;
        }
        BlockMiner.release();
        // Sand or gravel on its way down lands before you walk under it.
        if (somethingFalling()) {
            InputUtil.release(mc.options.keyUp);
            return;
        }
        InputUtil.hold(mc.options.keyUp);
        placeTorch();
    }

    private boolean somethingFalling() {
        AABB stretch = box(Math.max(1, standingDepth())).minmax(box(cleared + 1)).expandTowards(0, FALL_WATCH, 0);
        return !mc.level.getEntitiesOfClass(FallingBlockEntity.class, stretch).isEmpty();
    }

    private int standingDepth() {
        return (int) Math.floor(walker.travelled());
    }

    // A slice you stand in with nothing left in reach is given up.
    private BlockPos nextBlock() {
        double reach = mc.player.blockInteractionRange();
        // Sand or gravel that dropped into the stretch already dug comes out first. Nothing else
        // behind the face is touched and a torch put down there stays.
        for (int depth = Math.max(1, standingDepth()); depth <= cleared; depth++) {
            BlockPos fallen = BlockUtil.nearestOf(slice(depth),
                pos -> BlockUtil.state(pos).getBlock() instanceof FallingBlock && BlockUtil.diggable(pos)
                    && BlockUtil.reaches(pos, reach));
            if (fallen != null) {
                return fallen;
            }
        }
        for (int scanned = 0; scanned < SCAN_DEPTHS && cleared < length.getInt(); scanned++) {
            int depth = cleared + 1;
            List<BlockPos> face = slice(depth);
            BlockPos best = BlockUtil.nearestOf(face, pos -> BlockUtil.diggable(pos) && BlockUtil.reaches(pos, reach));
            if (best != null) {
                return best;
            }
            BlockPos outOfReach = BlockUtil.nearestOf(face,
                pos -> BlockUtil.diggable(pos) && !BlockUtil.reaches(pos, reach));
            if (outOfReach != null) {
                // Walking closer may still bring it in reach.
                if (depth > walker.travelled() + AxisWalker.BESIDE) {
                    return null;
                }
                if (!toldLeft) {
                    toldLeft = true;
                    tell("left a block it cannot reach at §f"
                        + BlockUtil.text(outOfReach) + "§7.");
                }
            }
            cleared++;
        }
        return null;
    }

    // True when a block went down this tick or the server has to catch up first. The tunnel ends at
    // a hole right ahead in the lane you walk with nothing in the hotbar to fill it.
    private boolean fillHole() {
        double reach = mc.player.blockInteractionRange();
        int here = Math.max(1, standingDepth());
        for (int depth = here; depth <= cleared + 1; depth++) {
            for (int lane = leftLane(); lane <= rightLane(); lane++) {
                BlockPos floor = walker.blockAt(depth, lane, -1);
                if (BlockUtil.isSolid(floor) || !BlockUtil.blockFits(floor)) {
                    continue;
                }
                int slot = BlockUtil.findBlockSlot(block -> BlockUtil.isBuildingBlock(block, floor));
                if (slot == -1) {
                    if (lane == 0 && depth <= here + 1) {
                        disable("Tunneller stopped at a hole in the floor with no block in the hotbar to fill it.");
                        return true;
                    }
                    continue;
                }
                BlockUtil.Placement click = BlockUtil.reachablePlacement(floor, reach, false);
                if (click == null) {
                    continue;
                }
                // Paper drops use packets past its limit. The hole waits for the next tick.
                if (UseBudget.remaining() == 0) {
                    InputUtil.release(mc.options.keyUp);
                    return true;
                }
                slots.select(slot);
                boolean placed = click.place(FaceMode.SERVER, SwingMode.CLIENT);
                slots.restoreIfMine();
                if (placed) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean sealLiquid() {
        double reach = mc.player.blockInteractionRange();
        for (int depth = Math.max(1, cleared); depth <= cleared + 1; depth++) {
            for (int lane = leftLane() - 1; lane <= rightLane() + 1; lane++) {
                for (int up = -1; up <= height.getInt(); up++) {
                    boolean inside = lane >= leftLane() && lane <= rightLane()
                        && up >= 0 && up < height.getInt();
                    BlockPos pos = walker.blockAt(depth, lane, up);
                    if (inside || mc.level.getFluidState(pos).isEmpty()) {
                        continue;
                    }
                    BlockUtil.Placement click = BlockUtil.reachablePlacement(pos, reach, false);
                    if (click == null) {
                        continue;
                    }
                    int slot = BlockUtil.findBlockSlot();
                    if (slot == -1) {
                        return false;
                    }
                    slots.select(slot);
                    boolean placed = click.place(FaceMode.SERVER, SwingMode.CLIENT);
                    slots.restoreIfMine();
                    if (placed) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<BlockPos> slice(int depth) {
        List<BlockPos> result = new ArrayList<>(width.getInt() * height.getInt());
        for (int lane = leftLane(); lane <= rightLane(); lane++) {
            for (int up = 0; up < height.getInt(); up++) {
                result.add(walker.blockAt(depth, lane, up));
            }
        }
        return result;
    }

    private int leftLane() {
        return AxisWalker.leftLane(width.getInt());
    }

    private int rightLane() {
        return AxisWalker.rightLane(width.getInt());
    }

    private void placeTorch() {
        if (!torches.isOn()) {
            return;
        }
        int depth = (int) Math.floor(walker.travelled());
        if (depth < 1 || depth - lastTorch < spacing.getInt()) {
            return;
        }
        BlockPos target = walker.blockAt(depth, leftLane(), 0);
        if (!BlockUtil.isReplaceable(target) || !BlockUtil.isSolid(target.below())) {
            return;
        }
        int slot = BlockUtil.findTorchSlot();
        if (slot == -1) {
            return;
        }
        slots.select(slot);
        if (BlockUtil.place(target, Direction.DOWN, true, true)) {
            lastTorch = depth;
        }
        slots.restoreIfMine();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!walker.isLocked() || !inGame()) {
            return;
        }
        int end = Math.min(cleared + 1, length.getInt());
        if (end >= 1) {
            AABB near = box(1);
            AABB far = box(end);
            event.getBatch().outlineBox(near.minmax(far).inflate(0.005), TUNNEL_COLOR, true);
        }
        if (current != null) {
            event.getBatch().outlineBlock(current, CURRENT_COLOR, false);
        }
    }

    private AABB box(int depth) {
        AABB first = new AABB(walker.blockAt(depth, leftLane(), 0));
        AABB last = new AABB(walker.blockAt(depth, rightLane(), height.getInt() - 1));
        return first.minmax(last);
    }
}
