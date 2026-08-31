package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Digs out a box marked with two corners. Blocks come out from the top down.
 */
public final class Excavator extends Module {

    private static final int BOX_COLOR = 0xFF40C0FF;
    private static final int CORNER_COLOR = 0xFFFFD040;
    private static final int CURRENT_COLOR = 0xFFFF5030;

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

    public enum Speed { LEGIT, INSTANT }

    // Ticks before a one hit block that did not vanish is sent again.
    private static final int RETRY_TICKS = 10;

    private BlockPos first;
    private BlockPos second;
    private final List<BlockPos> remaining = new ArrayList<>();
    private BlockPos current;
    private ClientLevel lastLevel;

    // One hit blocks already sent and the tick they may be sent again.
    private final Map<BlockPos, Integer> attempted = new HashMap<>();
    private int lastTick;

    public Excavator() {
        super("Excavator", "Digs out a box. Press the bind at each corner whilst it is on.", Category.WORLD);
        addSettings(range, maxBlocks, speed, perTick, rotate);
        searchTags("dig", "cuboid", "selection", "quarry");
    }

    @Override
    public String getSuffix() {
        if (first == null) {
            return "pick a corner";
        }
        if (second == null) {
            return "pick the other corner";
        }
        return remaining.size() + " left";
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        clear();
        lastLevel = mc.level;
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        clear();
        lastLevel = null;
    }

    // Bringing this back at launch would start digging at once.
    @Override
    public boolean savesEnabledState() {
        return false;
    }

    private void clear() {
        first = null;
        second = null;
        remaining.clear();
        attempted.clear();
        current = null;
    }

    /**
     * The bind marks the block under the crosshair whilst the module is on.
     * Pressing it with nothing in view turns the module off again.
     */
    @Override
    public void onKeybind() {
        if (!isEnabled() || !inGame()) {
            toggle();
            return;
        }
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            mark(hit.getBlockPos().immutable());
            return;
        }
        toggle();
    }

    private void mark(BlockPos pos) {
        if (first == null || second != null) {
            clear();
            first = pos;
            ChatUtil.message("§bExcavator §7first corner at §f" + text(pos) + "§7.");
            return;
        }
        second = pos;
        if (!build()) {
            second = null;
            return;
        }
        ChatUtil.message("§bExcavator §7second corner at §f" + text(pos)
            + "§7. §f" + remaining.size() + "§7 blocks to dig.");
    }

    // The top layers come first. Sand and gravel never drop onto a cleared spot.
    private boolean build() {
        remaining.clear();
        current = null;
        long volume = (long) (Math.abs(first.getX() - second.getX()) + 1)
            * (Math.abs(first.getY() - second.getY()) + 1)
            * (Math.abs(first.getZ() - second.getZ()) + 1);
        if (volume > maxBlocks.getInt()) {
            ChatUtil.error("That box holds " + volume + " blocks and the limit is "
                + maxBlocks.getInt() + ". Pick a smaller one.");
            return false;
        }
        Vec3 eye = mc.player.getEyePosition();
        for (BlockPos pos : BlockPos.betweenClosed(first, second)) {
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
        if (mc.level != lastLevel) {
            // Corners taken in another world point at nothing here.
            lastLevel = mc.level;
            clear();
            return;
        }
        if (second == null) {
            return;
        }
        // Holding attack means the player is mining by hand.
        if (InputUtil.physicallyHeld(mc.options.keyAttack) || mc.player.isUsingItem()) {
            return;
        }
        if (remaining.isEmpty()) {
            ChatUtil.message("§bExcavator §7finished that box.");
            clear();
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
    }

    /**
     * Sends the break packets for the one hit blocks in reach. True when any
     * went out. Slower blocks fall through to the held click path.
     */
    private boolean breakInstantly() {
        int now = mc.player.tickCount;
        if (now < lastTick) {
            attempted.clear();
        }
        lastTick = now;
        attempted.values().removeIf(expiry -> expiry <= now);
        int sent = 0;
        while (sent < perTick.getInt()) {
            BlockPos pos = next(candidate -> BlockUtil.canInstantBreak(candidate)
                && !attempted.containsKey(candidate));
            if (pos == null) {
                break;
            }
            BlockMiner.breakInstantly(pos);
            attempted.put(pos, now + RETRY_TICKS);
            current = pos;
            sent++;
        }
        if (sent > 0) {
            mc.player.swing(InteractionHand.MAIN_HAND);
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

    private static String text(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (first == null) {
            return;
        }
        if (second == null) {
            event.getBatch().outlineBox(new AABB(first).inflate(0.005), CORNER_COLOR, true);
            return;
        }
        AABB box = new AABB(first).minmax(new AABB(second));
        event.getBatch().outlineBox(box.inflate(0.005), BOX_COLOR, true);
        event.getBatch().outlineBox(new AABB(first).deflate(0.3), CORNER_COLOR, true);
        event.getBatch().outlineBox(new AABB(second).deflate(0.3), CORNER_COLOR, true);
        if (current != null) {
            event.getBatch().outlineBlock(current, CURRENT_COLOR, false);
        }
    }
}
