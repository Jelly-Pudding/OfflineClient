package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Breaks every block around the player. Legit mode mines one block at a time
 * like a held click while instant mode fires the break packets straight
 * away.
 */
public final class Nuker extends Module {

    public enum Mode {
        ALL("All"),
        SELECTED("Selected");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Speed {
        LEGIT("Legit"),
        INSTANT("Instant");

        private final String label;

        Speed(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Order {
        NEAREST("Nearest"),
        FASTEST("Fastest");

        private final String label;

        Order(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final int OUTLINE_COLOR = 0xFFFF4040;
    private static final int RETRY_TICKS = 10;
    private static final int SLOW_GRACE_TICKS = 20;

    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes to break blocks.", 4.5, 1, 6, 0.1).min(1);
    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "All breaks everything. Selected only breaks the block type you were looking at when you turned it on. Left click a block to pick another type.",
        Mode.ALL);
    private final BoolSetting flat = new BoolSetting("Flat",
        "Only breaks blocks at your feet or higher so you never dig a hole under yourself.", false);
    private final EnumSetting<Speed> speed = new EnumSetting<>("Speed",
        "Legit mines one block at a time and waits for it to break. Instant sends break packets and is only quick for blocks that break in one hit.",
        Speed.LEGIT);
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many one hit blocks to break each tick in Instant mode.", 4, 1, 16, 1)
        .min(1).visibleWhen(() -> speed.is(Speed.INSTANT));
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns toward the block on the server side. Your own view never moves.", true)
        .visibleWhen(() -> speed.is(Speed.LEGIT));
    private final EnumSetting<Order> order = new EnumSetting<>("Order",
        "Nearest breaks the closest block first. Fastest breaks what goes quickest first.",
        Order.NEAREST).visibleWhen(() -> speed.is(Speed.LEGIT));
    private final ColorSetting highlight = new ColorSetting("Highlight",
        "Color of the boxes on the blocks about to break.", 10, false);
    private final NumberSetting highlightStrength = new NumberSetting("Highlight strength",
        "How strong the boxes show. Zero hides them.", 35, 0, 100, 5, "%").max(100);

    private Block selected;
    private BlockPos current;
    private final Map<BlockPos, Integer> attempted = new HashMap<>();
    private BlockPos slowPending;
    private int slowDeadline;

    public Nuker() {
        super("Nuker", "Breaks all blocks around you.", Category.WORLD);
        addSettings(range, mode, flat, speed, perTick, rotate, order, highlight, highlightStrength);
        searchTags("dig", "excavate", "break blocks");
    }

    @Override
    public String getSuffix() {
        if (mode.is(Mode.SELECTED)) {
            return selected == null ? "Nothing selected" : BlockUtil.blockName(selected);
        }
        return range.getValueString();
    }

    @Override
    protected void onEnable() {
        current = null;
        attempted.clear();
        slowPending = null;
        // Only one module may drive BlockMiner.
        ModuleManager modules = OfflineClient.INSTANCE.getModuleManager();
        if (modules != null) {
            modules.get(VeinMiner.class).setEnabled(false);
        }
        if (mode.is(Mode.SELECTED) && inGame()) {
            selectLookedAtBlock();
        }
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        current = null;
        attempted.clear();
        slowPending = null;
    }

    /** Picks up the block type under the crosshair. */
    private void selectLookedAtBlock() {
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            select(hit.getBlockPos());
        }
    }

    private void select(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        if (state.isAir() || state.getBlock() == selected) {
            return;
        }
        selected = state.getBlock();
        current = null;
        ChatUtil.message("§bNuker §7now breaks §f" + BlockUtil.blockName(selected) + "§7.");
    }

    /** A real click on a block in Selected mode picks that block type. */
    @Subscribe
    private void onBlockBreak(BlockBreakEvent event) {
        if (BlockMiner.isSelfCall() || !mode.is(Mode.SELECTED) || !inGame()) {
            return;
        }
        select(event.getPos());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        // Holding attack means the player is mining by hand.
        if (mc.options.keyAttack.isDown() || mc.player.isUsingItem()) {
            current = null;
            return;
        }
        if (mode.is(Mode.SELECTED) && selected == null) {
            return;
        }
        if (speed.is(Speed.LEGIT)) {
            mineLegit();
        } else {
            mineInstant();
        }
    }

    /** Sticks with one block until it is gone then moves to the next nearest. */
    private void mineLegit() {
        if (current != null && !wanted(current)) {
            current = null;
        }
        if (current == null) {
            current = nearest();
        }
        if (current == null) {
            return;
        }
        if (!BlockMiner.mine(current, rotate.isOn())) {
            current = null;
        }
    }

    /**
     * One hit blocks get their packets right away up to the per tick limit.
     * A slower block is only queued when the previous one is gone or its
     * expected break time has run out.
     */
    private void mineInstant() {
        int now = mc.player.tickCount;
        attempted.values().removeIf(expiry -> expiry <= now);
        if (slowPending != null && (!wanted(slowPending) || now >= slowDeadline)) {
            slowPending = null;
        }

        int sent = 0;
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (sent >= perTick.getInt()) {
                break;
            }
            if (!wanted(pos) || attempted.containsKey(pos)) {
                continue;
            }
            if (BlockUtil.canInstantBreak(pos)) {
                BlockMiner.breakInstantly(pos);
                attempted.put(pos, now + RETRY_TICKS);
                sent++;
            } else if (slowPending == null) {
                BlockMiner.breakInstantly(pos);
                slowPending = pos;
                slowDeadline = now + BlockUtil.breakTicks(pos) + SLOW_GRACE_TICKS;
                attempted.put(pos, slowDeadline);
                sent++;
            }
        }
        if (sent > 0) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private BlockPos nearest() {
        BlockPos best = null;
        int bestTicks = Integer.MAX_VALUE;
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (!wanted(pos)) {
                continue;
            }
            if (order.is(Order.NEAREST)) {
                return pos;
            }
            int ticks = BlockUtil.breakTicks(pos);
            if (ticks < bestTicks) {
                bestTicks = ticks;
                best = pos;
            }
        }
        return best;
    }

    /** True if the block passes every filter and is safe to remove. */
    private boolean wanted(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        if (state.isAir() || !BlockUtil.isBreakable(pos)) {
            return false;
        }
        if (state.getShape(mc.level, pos).isEmpty()) {
            return false;
        }
        if (mode.is(Mode.SELECTED) && state.getBlock() != selected) {
            return false;
        }
        if (flat.isOn() && pos.getY() + 0.5 < mc.player.getY()) {
            return false;
        }
        if (BlockUtil.isStandingOn(pos)) {
            return false;
        }
        return BlockUtil.distanceTo(pos) <= range.getValue();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        int strength = (int) (255 * highlightStrength.getValue() / 100);
        if (inGame() && strength > 0) {
            // Boxes on every qualifying block up to a cap.
            int argb = ColorUtil.withAlpha(highlight.getColor(), strength);
            int shown = 0;
            for (BlockPos candidate : BlockUtil.positionsWithin(range.getValue())) {
                if (shown >= 64) {
                    break;
                }
                if (!wanted(candidate)) {
                    continue;
                }
                event.getBatch().outlineBox(new AABB(candidate).deflate(0.01), argb, false);
                shown++;
            }
        }
        BlockPos pos = speed.is(Speed.LEGIT) ? current : slowPending;
        if (pos == null || BlockUtil.state(pos).isAir()) {
            return;
        }
        event.getBatch().outlineBox(new AABB(pos).deflate(0.002),
            ColorUtil.withAlpha(highlight.getColor(), 255), false);
    }
}
