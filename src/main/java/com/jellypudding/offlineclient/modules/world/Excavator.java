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
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.PacketBreaker;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

// Digs out a box marked with two corners. Blocks come out from the top down.
public final class Excavator extends Module {

    private static final int CORNER_COLOR = 0xFFFFD040;

    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes a block may be.",
        4.5, 1, 6, 0.1).min(1).max(6);
    private final NumberSetting maxBlocks = new NumberSetting("Max blocks",
        "The most blocks one box may hold.", 4096, 64, 16384, 64)
        .min(1).max(65536);
    private final EnumSetting<Speed> speed = new EnumSetting<>("Speed",
        "How the blocks come down.", Speed.LEGIT)
        .describe(Speed.LEGIT, "One block at a time like a held click.")
        .describe(Speed.INSTANT, "Fires the break packets for several one hit blocks at once.");
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many one hit blocks to break each tick in Instant mode.", 4, 1, 16, 1)
        .min(1).under(speed, Speed.INSTANT);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards each block on the server side.", true);
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

    private final CornerPicker corners = new CornerPicker();
    private final List<BlockPos> remaining = new ArrayList<>();
    private BlockPos current;
    private final WorldWatch world = new WorldWatch();

    private final PacketBreaker breaker = new PacketBreaker();

    public Excavator() {
        super("Excavator", "Digs out a box. Press the bind at each corner whilst it is on.", Category.WORLD);
        addSettings(range, maxBlocks, speed, perTick, rotate, walkTo, keepActive, logSelection);
        addSettings(boxStyle.settings());
        addSettings(targetStyle.settings());
        searchTags("dig", "cuboid", "selection", "quarry");
    }

    @Override
    public String getSuffix() {
        String prompt = corners.prompt();
        return prompt != null ? prompt : remaining.size() + " left";
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        clear();
        world.accept();
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        clear();
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

    // The bind marks the block under the crosshair whilst the module is on.
    // Pressing it with nothing in view turns the module off again.
    @Override
    public void onKeybind() {
        if (!isEnabled() || !inGame()) {
            toggle();
            return;
        }
        BlockHitResult hit = BlockUtil.aimedBlock();
        if (hit != null) {
            mark(hit.getBlockPos().immutable());
            return;
        }
        toggle();
    }

    private void mark(BlockPos pos) {
        if (!corners.started() || corners.done()) {
            clear();
            corners.mark(pos);
            ChatUtil.message("§bExcavator §7first corner at §f" + BlockUtil.text(pos) + "§7.");
            return;
        }
        corners.mark(pos);
        if (!build()) {
            corners.undoSecond();
            return;
        }
        if (logSelection.isOn()) {
            ChatUtil.message("§bExcavator §7second corner at §f" + BlockUtil.text(pos)
                + "§7. §f" + remaining.size() + "§7 blocks to dig.");
        }
    }

    // The top layers come first. Sand and gravel never drop onto a cleared spot.
    private boolean build() {
        remaining.clear();
        current = null;
        long volume = corners.volume();
        if (volume > maxBlocks.getInt()) {
            ChatUtil.error("That box holds " + volume + " blocks and the limit is "
                + maxBlocks.getInt() + ". Pick a smaller one.");
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
        if (!inGame() || mc.player.isSpectator()) {
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
        if (InputUtil.physicallyHeld(mc.options.keyAttack) || mc.player.isUsingItem()) {
            stopWalking();
            return;
        }
        if (remaining.isEmpty()) {
            ChatUtil.message("§bExcavator §7finished that box.");
            clear();
            if (!keepActive.isOn()) {
                setEnabled(false);
            }
            return;
        }
        if (speed.is(Speed.INSTANT) && breakInstantly()) {
            return;
        }
        current = next(pos -> true);
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
            BlockPos target = closestLeft();
            if (target != null) {
                finder.search(PathFinder.standingAt(mc.player),
                    new PathGoal.Around(target, Math.max(1, range.getValue() - 1)));
            }
            return;
        }
        walker.tick(finder.liveRules());
    }

    private BlockPos closestLeft() {
        Vec3 eye = mc.player.getEyePosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : remaining) {
            double distance = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    private void stopWalking() {
        finder.cancel();
        walker.stop();
    }

    // Sends the break packets for the one hit blocks in reach. True when any
    // went out. Slower blocks fall through to the held click path.
    private boolean breakInstantly() {
        breaker.tick();
        int sent = 0;
        while (sent < perTick.getInt()) {
            BlockPos pos = next(breaker::canSendInstant);
            if (pos == null) {
                break;
            }
            breaker.send(pos);
            current = pos;
            sent++;
        }
        if (sent > 0) {
            SwingMode.swingArm(InteractionHand.MAIN_HAND);
        }
        return sent > 0;
    }

    // Blocks that are already gone drop out of the queue on the way past.
    private BlockPos next(Predicate<BlockPos> wanted) {
        Iterator<BlockPos> it = remaining.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            if (!BlockUtil.diggable(pos)) {
                it.remove();
                continue;
            }
            // The block underfoot waits until the player has moved off it.
            if (!BlockUtil.isStandingOn(pos) && BlockUtil.distanceTo(pos) <= range.getValue()
                && wanted.test(pos)) {
                return pos;
            }
        }
        return null;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!corners.started()) {
            return;
        }
        boxStyle.draw(event.getBatch(), corners.box().inflate(0.005), true);
        if (!corners.done()) {
            return;
        }
        event.getBatch().outlineBox(new AABB(corners.first()).deflate(0.3), CORNER_COLOR, true);
        event.getBatch().outlineBox(new AABB(corners.second()).deflate(0.3), CORNER_COLOR, true);
        if (current != null) {
            targetStyle.draw(event.getBatch(), current, false);
        }
    }
}
