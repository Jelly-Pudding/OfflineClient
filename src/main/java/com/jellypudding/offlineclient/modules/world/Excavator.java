package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.path.PathFinder;
import com.jellypudding.offlineclient.path.PathGoal;
import com.jellypudding.offlineclient.path.PathWalker;
import com.jellypudding.offlineclient.util.CornerPicker;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.PacketBreaker;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

// Digs out a box marked with two corners. Blocks come out from the top down.
public final class Excavator extends Module implements CornerPicker.Taker {

    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes a block may be.",
        4.5, 1, 6, 0.1, " blocks");
    private final NumberSetting maxBlocks = CornerPicker.maxBlocks();
    private final EnumSetting<Speed> speed = new EnumSetting<>("Speed",
        "How the blocks come down.", Speed.LEGIT)
        .describe(Speed.LEGIT, "One block at a time like a held click.")
        .describe(Speed.INSTANT, "Fires the break packets for every block a single tick breaks.");
    private final NumberSetting perTick = PacketBreaker.blocksPerTick().under(speed, Speed.INSTANT);
    private final RegistryListSetting<Block> skipBlocks = new RegistryListSetting<>("Skip blocks",
        "Blocks inside the box that are left standing. Click to pick them.", BuiltInRegistries.BLOCK, List.of());
    private final BoolSetting autoTool = new BoolSetting("Auto tool",
        "Holds the tool AutoTool would pick or else your fastest one.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards each block on the server side.", true);
    private final BoolSetting walkTo = new BoolSetting("Walk to blocks",
        "Walks to the next block whilst it is out of reach.", false);
    private final BoolSetting keepActive = new BoolSetting("Keep active",
        "Stays on after the box is done ready for another.", false);
    private final BoolSetting logSelection = new BoolSetting("Log selection",
        "Prints each corner you mark to chat.", true);
    private final BoxStyle boxStyle = BoxStyle.white(BoxStyle.Shape.BOTH);
    private final BoxStyle targetStyle = new BoxStyle("Target", BoxStyle.Shape.BOTH, 0);

    public enum Speed { LEGIT, INSTANT }

    private final PathFinder finder = new PathFinder();
    private final PathWalker walker = new PathWalker();

    private final CornerPicker corners = new CornerPicker(this, boxStyle, maxBlocks::getInt, "dug out");
    private final List<BlockPos> remaining = new ArrayList<>();
    private BlockPos current;
    private final WorldWatch world = new WorldWatch();

    private final PacketBreaker breaker = new PacketBreaker();
    private final SlotSwap slots = new SlotSwap();

    public Excavator() {
        super("Excavator", "Digs out the box between two opposite corners you click with the use key.",
            Category.WORLD);
        addSettings(range, maxBlocks, speed, perTick, skipBlocks, autoTool, rotate, walkTo, keepActive, logSelection);
        addSettings(boxStyle.settings());
        addSettings(targetStyle.settings());
        searchTags("dig", "cuboid", "selection", "quarry");
    }

    @Override
    public String getSuffix() {
        String waiting = corners.waitingFor();
        return waiting != null ? waiting : remaining.size() + " left";
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        clear();
        corners.open();
        world.accept();
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        slots.restoreIfMine();
        clear();
        corners.close();
        world.forget();
    }

    // Bringing this back at launch would start digging at once.
    @Override
    public boolean savesEnabledState() {
        return false;
    }

    private void clear() {
        stopWalking();
        corners.clear();
        remaining.clear();
        breaker.reset();
        current = null;
    }

    @Override
    public void markCorner(BlockPos pos) {
        corners.mark(pos);
        if (!corners.done()) {
            if (logSelection.isOn()) {
                tell("first corner at §f" + BlockUtil.text(pos) + "§7.");
            }
            return;
        }
        if (build() && logSelection.isOn()) {
            tell("second corner at §f" + BlockUtil.text(pos)
                + "§7. §f" + remaining.size() + "§7 blocks to dig.");
        }
    }

    // The top layers come first. Sand and gravel never drop onto a cleared spot.
    private boolean build() {
        remaining.clear();
        current = null;
        if (!corners.withinLimit()) {
            return false;
        }
        Vec3 eye = mc.player.getEyePosition();
        for (BlockPos pos : BlockPos.betweenClosed(corners.first(), corners.second())) {
            remaining.add(pos.immutable());
        }
        Comparator<BlockPos> topDownThenNear = Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed()
            .thenComparingDouble(pos -> eye.distanceToSqr(Vec3.atCenterOf(pos)));
        remaining.sort(topDownThenNear);
        return true;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        current = null;
        if (Hands.tied()) {
            return;
        }
        if (world.changed()) {
            // Corners taken in another world point at nothing here.
            clear();
            return;
        }
        if (!corners.done()) {
            return;
        }
        // Holding attack means the player is mining by hand.
        if (InputUtil.physicallyHeld(mc.options.keyAttack) || mc.player.isUsingItem() || Hands.slotsTaken()) {
            stopWalking();
            slots.standAside();
            return;
        }
        if (remaining.isEmpty()) {
            tell("finished that box.");
            slots.restoreIfMine();
            clear();
            if (!keepActive.isOn()) {
                setEnabled(false);
            }
            return;
        }
        boolean instant = speed.is(Speed.INSTANT);
        List<BlockPos> inReach = inReach(instant);
        if (instant && breakInstantly(inReach)) {
            return;
        }
        current = inReach.isEmpty() ? null : inReach.getFirst();
        holdTool(current);
        if (current != null && !BlockMiner.mine(current, rotate.isOn())) {
            remaining.remove(current);
            current = null;
        }
        if (current == null && walkTo.isOn() && !remaining.isEmpty()) {
            walkOn();
        } else {
            stopWalking();
        }
    }

    // Heads for the closest block left whilst none of them is in reach.
    private void walkOn() {
        PathFinder.Result result = finder.poll();
        if (result != null) {
            walker.follow(result.nodes());
        }
        if (finder.busy()) {
            return;
        }
        if (walker.arrived() || walker.lost()) {
            BlockPos target = BlockUtil.nearestOf(remaining, pos -> true);
            if (target != null) {
                finder.search(PathFinder.standingAt(mc.player),
                    new PathGoal.Around(target, Math.max(1, range.getValue() - 1)));
            }
            return;
        }
        walker.tick(finder.liveRules());
    }

    private void stopWalking() {
        finder.cancel();
        walker.stop();
    }

    // Sends the break packets for the blocks in reach a single tick breaks. True when
    // any went out. Slower blocks fall through to the held click path.
    private boolean breakInstantly(List<BlockPos> inReach) {
        breaker.tick();
        // The tool has to be in hand before anything is judged a one hit block.
        BlockPos slow = breaker.slowBlock();
        holdTool(slow != null ? slow : inReach.isEmpty() ? null : inReach.getFirst());
        int sent = 0;
        for (BlockPos pos : inReach) {
            if (sent >= perTick.getInt()) {
                break;
            }
            if (sendsInstantly(pos)) {
                breaker.send(pos);
                current = pos;
                sent++;
            }
        }
        if (sent > 0) {
            SwingMode.BOTH.mine();
        }
        return sent > 0;
    }

    // A block whose Silk Touch tool AutoTool would borrow from the inventory takes the held click.
    private boolean sendsInstantly(BlockPos pos) {
        return breaker.canSendInstant(pos) && !Modules.toolRefuses(BlockUtil.state(pos), true);
    }

    // The tool for the block about to be broken. Nothing to break gives the slot back.
    private void holdTool(BlockPos pos) {
        if (pos == null || !autoTool.isOn()) {
            slots.restoreIfMine();
            return;
        }
        ItemUtil.holdBestTool(BlockUtil.state(pos), slots);
    }

    // Every block in reach in queue order or only the first. Blocks that are gone or are to be left
    // standing drop out of the queue on the way past. AutoTool leaves standing a listed block it has
    // no Silk Touch tool for.
    private List<BlockPos> inReach(boolean every) {
        List<BlockPos> found = new ArrayList<>();
        Iterator<BlockPos> it = remaining.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            boolean near = BlockUtil.distanceTo(pos) <= range.getValue();
            // Past the first block in reach one out of reach costs only its distance.
            if (!near && !found.isEmpty()) {
                continue;
            }
            BlockState state = BlockUtil.state(pos);
            if (!BlockUtil.diggable(pos) || skipBlocks.contains(state.getBlock())
                || Modules.toolRefuses(state, false)) {
                it.remove();
                continue;
            }
            // The block underfoot waits until the player has moved off it.
            if (near && !BlockUtil.isStandingOn(pos)) {
                found.add(pos);
                if (!every) {
                    break;
                }
            }
        }
        return found;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (corners.done() && current != null) {
            targetStyle.draw(event.getBatch(), current, false);
        }
    }
}
