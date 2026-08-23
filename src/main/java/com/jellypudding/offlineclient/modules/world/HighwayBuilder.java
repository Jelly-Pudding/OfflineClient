package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.AxisWalker;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a highway along one axis at the height it starts from. One phase runs
 * per tick.
 */
public final class HighwayBuilder extends Module {


    private static final int TUNNEL_COLOR = 0xFF40C0FF;
    private static final int MINE_COLOR = 0xFFFF5030;
    private static final int PAVE_COLOR = 0xFF50FF80;

    // Finished blocks that must lie ahead before walking on.
    private static final double LEAD = 1.5;

    // Ticks of no progress before the module gives up.
    private static final int IDLE_LIMIT = 100;

    // Depths looked at in one tick. Keeps a long clear stretch cheap.
    private static final int SCAN_DEPTHS = 8;

    private final NumberSetting width = new NumberSetting("Width",
        "How wide the highway is.", 4, 1, 5, 1, " blocks").min(1).max(9);
    private final NumberSetting height = new NumberSetting("Height",
        "How tall the cleared tunnel is.", 3, 2, 5, 1, " blocks").min(2).max(9);
    private final BoolSetting pave = new BoolSetting("Pave",
        "Fills the floor under the highway as you go.", true);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks allowed in the floor. The first one you carry gets used.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.OBSIDIAN, Blocks.NETHERRACK, Blocks.BLACKSTONE, Blocks.BASALT,
            Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE))
        .visibleWhen(pave::isOn);
    private final BoolSetting stopWhenEmpty = new BoolSetting("Stop when empty",
        "Turns the module off once you run out of floor blocks.", true)
        .visibleWhen(pave::isOn);

    private final AxisWalker walker = new AxisWalker();
    private int cleared;
    private int paved;
    private int idleTicks;
    private double bestTravelled;

    private BlockPos mineTarget;
    private BlockPos paveTarget;

    public HighwayBuilder() {
        super("HighwayBuilder", "Digs and paves a highway along one axis at a fixed height.", Category.WORLD);
        addSettings(width, height, pave, blocks, stopWhenEmpty);
        searchTags("highway", "nether", "tunnel", "road");
    }

    @Override
    public String getSuffix() {
        if (!walker.isLocked()) {
            return null;
        }
        return walker.axis().getName() + " " + (int) Math.max(0, bestTravelled);
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
        mineTarget = null;
        paveTarget = null;
        if (inGame()) {
            lockAxis();
        }
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        mc.options.keyUp.setDown(false);
        walker.clear();
        mineTarget = null;
        paveTarget = null;
    }

    private void lockAxis() {
        walker.lock();
        cleared = 0;
        paved = 0;
        idleTicks = 0;
        bestTravelled = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        mineTarget = null;
        paveTarget = null;
        if (!inGame()) {
            return;
        }
        if (!walker.isLocked()) {
            lockAxis();
        }
        if (!safeToWork()) {
            return;
        }

        walker.holdAxis();
        trackProgress();

        if (clearAhead()) {
            return;
        }
        if (pave.isOn() && paveAhead()) {
            return;
        }
        // Running out of floor blocks turns the module off inside that phase.
        if (!isEnabled()) {
            return;
        }
        walk();
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
        if (idleTicks > IDLE_LIMIT) {
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

    // Breaks one block out of the tunnel ahead. True when it took the tick.
    private boolean clearAhead() {
        double reach = mc.player.blockInteractionRange();
        for (int scanned = 0; scanned < SCAN_DEPTHS; scanned++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            boolean anyLeft = false;
            for (BlockPos pos : tunnelSlice(cleared + 1)) {
                if (!BlockUtil.diggable(pos)) {
                    continue;
                }
                anyLeft = true;
                double distance = BlockUtil.distanceTo(pos);
                if (distance <= reach && distance < bestDistance) {
                    bestDistance = distance;
                    best = pos;
                }
            }
            if (!anyLeft) {
                cleared++;
                continue;
            }
            if (best == null) {
                return false;
            }
            mineTarget = best;
            idleTicks = 0;
            mc.options.keyUp.setDown(false);
            if (!BlockMiner.mine(best, true)) {
                mineTarget = null;
                return false;
            }
            return true;
        }
        return false;
    }

    // Fills one gap in the floor ahead. True when it took the tick.
    private boolean paveAhead() {
        double reach = mc.player.blockInteractionRange();
        for (int scanned = 0; scanned < SCAN_DEPTHS; scanned++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            boolean anyLeft = false;
            for (BlockPos pos : floorSlice(paved + 1)) {
                if (!BlockUtil.isReplaceable(pos) || BlockUtil.intersectsPlayer(pos)) {
                    continue;
                }
                anyLeft = true;
                double distance = BlockUtil.distanceTo(pos);
                if (distance <= reach && distance < bestDistance) {
                    bestDistance = distance;
                    best = pos;
                }
            }
            if (!anyLeft) {
                paved++;
                continue;
            }
            if (best == null) {
                return false;
            }
            paveTarget = best;
            return place(best);
        }
        return false;
    }

    private boolean place(BlockPos target) {
        int slot = BlockUtil.findBlockSlot(this::allowed);
        if (slot == -1) {
            if (stopWhenEmpty.isOn()) {
                stop("HighwayBuilder stopped because you ran out of floor blocks.");
            }
            return false;
        }
        Direction support = BlockUtil.findPlaceSupport(target);
        if (support == null) {
            return false;
        }
        int previous = mc.player.getInventory().getSelectedSlot();
        if (slot != previous) {
            mc.player.getInventory().setSelectedSlot(slot);
        }
        boolean placed = BlockUtil.place(target, support, true, true);
        if (slot != previous) {
            mc.player.getInventory().setSelectedSlot(previous);
        }
        if (placed) {
            idleTicks = 0;
            mc.options.keyUp.setDown(false);
        }
        return placed;
    }

    private void walk() {
        BlockMiner.release();
        int ready = pave.isOn() ? Math.min(cleared, paved) : cleared;
        mc.options.keyUp.setDown(ready >= walker.travelled() + LEAD);
    }

    private boolean allowed(Block block) {
        if (blocks.size() == 0) {
            return false;
        }
        return blocks.contains(block);
    }

    private List<BlockPos> tunnelSlice(int depth) {
        List<BlockPos> result = new ArrayList<>(width.getInt() * height.getInt());
        for (int lane = leftLane(); lane <= rightLane(); lane++) {
            for (int up = 0; up < height.getInt(); up++) {
                result.add(walker.blockAt(depth, lane, up));
            }
        }
        return result;
    }

    private List<BlockPos> floorSlice(int depth) {
        List<BlockPos> result = new ArrayList<>(width.getInt());
        for (int lane = leftLane(); lane <= rightLane(); lane++) {
            result.add(walker.blockAt(depth, lane, -1));
        }
        return result;
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
        AABB near = new AABB(walker.blockAt(1, leftLane(), -1));
        AABB far = new AABB(walker.blockAt(Math.max(1, cleared), rightLane(), height.getInt() - 1));
        event.getBatch().outlineBox(near.minmax(far).inflate(0.005), TUNNEL_COLOR, true);
        if (mineTarget != null) {
            event.getBatch().outlineBox(new AABB(mineTarget).deflate(0.002), MINE_COLOR, false);
        }
        if (paveTarget != null) {
            event.getBatch().outlineBox(new AABB(paveTarget).deflate(0.002), PAVE_COLOR, false);
        }
    }
}
