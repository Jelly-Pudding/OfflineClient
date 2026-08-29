package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Legit mode mines one block at a time like a held click. Instant mode fires
 * the break packets straight away.
 */
public final class Nuker extends Module {

    public enum Mode { ALL, SELECTED, LIST }

    public enum ListMode { WHITELIST, BLACKLIST }

    public enum Speed { LEGIT, INSTANT }

    public enum Order { NEAREST, FASTEST }

    private static final int RETRY_TICKS = 10;
    private static final int SLOW_GRACE_TICKS = 20;

    private static final int MAX_HIGHLIGHTS = 64;

    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes to break blocks.",
        4.5, 1, 6, 0.1).min(1).max(6);
    private final NumberSetting wallsRange = new NumberSetting("Walls range",
        "How far to break blocks with no clear view from your eyes.",
        4.5, 0, 6, 0.1).min(0).max(6);
    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Which blocks the module is allowed to break.", Mode.ALL)
        .describe(Mode.ALL, "Every block in range.")
        .describe(Mode.SELECTED, "Only the block type you click.")
        .describe(Mode.LIST, "Whatever the list below allows.");
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<Block>("Blocks",
        "The blocks the list applies to. Click to pick them.", BuiltInRegistries.BLOCK,
        List.of(Blocks.BEDROCK, Blocks.BARRIER, Blocks.REINFORCED_DEEPSLATE,
            Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.END_PORTAL_FRAME,
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST,
            Blocks.BARREL, Blocks.SHULKER_BOX))
        .under(mode, Mode.LIST);
    private final EnumSetting<ListMode> listMode = new EnumSetting<>("List mode",
        "What the list means.", ListMode.BLACKLIST)
        .describe(ListMode.WHITELIST, "The list is what gets broken.")
        .describe(ListMode.BLACKLIST, "The list is what gets left alone.")
        .under(mode, Mode.LIST);
    private final BoolSetting lockTarget = new BoolSetting("Lock target",
        "Keeps the block you picked instead of following your next click.", false)
        .under(mode, Mode.SELECTED);
    private final BoolSetting flat = new BoolSetting("Flat",
        "Only breaks blocks at your feet or higher.", false);
    private final BoolSetting smash = new BoolSetting("Smash",
        "Only breaks blocks with no hardness such as plants and torches.", false);
    private final EnumSetting<Speed> speed = new EnumSetting<>("Speed",
        "How the blocks come down.", Speed.LEGIT)
        .describe(Speed.LEGIT, "One block at a time like a held click.")
        .describe(Speed.INSTANT, "Fires the break packets for several at once.");
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many one hit blocks to break each tick in Instant mode.", 4, 1, 16, 1)
        .min(1).under(speed, Speed.INSTANT);
    private final BoolSetting autoTool = new BoolSetting("Auto tool",
        "Holds your fastest tool before the break packets go out.", true)
        .under(speed, Speed.INSTANT);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn toward the block on the server side.", true)
        .under(speed, Speed.LEGIT);
    private final EnumSetting<Order> order = new EnumSetting<>("Order",
        "Which block gets broken first.", Order.NEAREST)
        .describe(Order.NEAREST, "The closest block first.")
        .describe(Order.FASTEST, "The quickest block to break first.")
        .under(speed, Speed.LEGIT);
    private final ColorSetting highlight = new ColorSetting("Highlight",
        "Colour of the boxes on the blocks about to break.", 10, false);
    private final NumberSetting highlightStrength = new NumberSetting("Highlight strength",
        "How strongly the boxes show.", 35, 0, 100, 5, "%").max(100);

    private Block selected;
    private BlockPos current;
    private final Map<BlockPos, Integer> attempted = new HashMap<>();
    private BlockPos slowPending;
    private int slowDeadline;
    private int lastTick;

    private final SlotSwap slots = new SlotSwap();

    // Filled once a tick and read by the renderer.
    private final List<BlockPos> highlights = new ArrayList<>();

    // A block is scanned several times a tick and one raycast each covers them all.
    private final Map<BlockPos, Boolean> sightCache = new HashMap<>();

    public Nuker() {
        super("Nuker", "Breaks all blocks around you.", Category.WORLD);
        addSettings(range, wallsRange, mode, blocks, listMode, lockTarget, flat, smash,
            speed, perTick, autoTool, rotate, order, highlight, highlightStrength);
        searchTags("dig", "excavate", "break blocks");
    }

    @Override
    public String getSuffix() {
        if (mode.is(Mode.SELECTED)) {
            return selected == null ? "Nothing selected" : BlockUtil.blockName(selected);
        }
        if (mode.is(Mode.LIST)) {
            return listMode.getValueString();
        }
        return range.getValueString();
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        current = null;
        attempted.clear();
        highlights.clear();
        sightCache.clear();
        slowPending = null;
        lastTick = 0;
        if (mode.is(Mode.SELECTED) && inGame()) {
            selectLookedAtBlock();
        }
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        slots.restoreIfMine();
        current = null;
        attempted.clear();
        highlights.clear();
        sightCache.clear();
        slowPending = null;
    }

    private void selectLookedAtBlock() {
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            select(hit.getBlockPos());
        }
    }

    private void select(BlockPos pos) {
        if (lockTarget.isOn() && selected != null) {
            return;
        }
        BlockState state = BlockUtil.state(pos);
        if (state.isAir() || state.getBlock() == selected) {
            return;
        }
        selected = state.getBlock();
        current = null;
        ChatUtil.message("§bNuker §7now breaks §f" + BlockUtil.blockName(selected) + "§7.");
    }

    // A real click on a block in Selected mode picks that block type.
    @Subscribe
    private void onBlockBreak(BlockBreakEvent event) {
        if (BlockMiner.isSelfCall() || !mode.is(Mode.SELECTED) || !inGame()) {
            return;
        }
        select(event.getPos());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        highlights.clear();
        sightCache.clear();
        if (!inGame() || mc.player.isSpectator()) {
            current = null;
            slots.restoreIfMine();
            return;
        }
        // Holding attack means the player is mining by hand.
        if (mc.options.keyAttack.isDown() || mc.player.isUsingItem()) {
            current = null;
            slots.restoreIfMine();
            return;
        }
        if (mode.is(Mode.SELECTED) && selected == null) {
            slots.restoreIfMine();
            return;
        }
        // One scan a tick feeds both the mining and the boxes.
        List<BlockPos> scan = BlockUtil.positionsWithin(range.getValue());
        collectHighlights(scan);
        if (speed.is(Speed.LEGIT)) {
            slots.restoreIfMine();
            mineLegit(scan);
        } else {
            mineInstant(scan);
        }
    }

    private void collectHighlights(List<BlockPos> scan) {
        if (highlightStrength.getValue() <= 0) {
            return;
        }
        for (BlockPos pos : scan) {
            if (highlights.size() >= MAX_HIGHLIGHTS) {
                return;
            }
            if (wanted(pos)) {
                highlights.add(pos);
            }
        }
    }

    private void mineLegit(List<BlockPos> scan) {
        if (current != null && !wanted(current)) {
            current = null;
        }
        if (current == null) {
            current = nearest(scan);
        }
        if (current == null) {
            return;
        }
        if (!BlockMiner.mine(current, rotate.isOn())) {
            current = null;
        }
    }

    /**
     * One hit blocks get their packets right away. A slower block is only queued
     * when the previous one is gone or its break time has run out.
     */
    private void mineInstant(List<BlockPos> scan) {
        int now = mc.player.tickCount;
        // The tick count restarts on a respawn.
        if (now < lastTick) {
            attempted.clear();
            slowPending = null;
        }
        lastTick = now;
        attempted.values().removeIf(expiry -> expiry <= now);
        if (slowPending != null && (!wanted(slowPending) || now >= slowDeadline)) {
            slowPending = null;
        }
        if (autoTool.isOn()) {
            // The tool has to be in hand before anything is judged a one hit block.
            holdTool(slowPending != null ? slowPending : firstWanted(scan));
        }

        int sent = 0;
        for (BlockPos pos : scan) {
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

    /**
     * Instant mode never reaches the vanilla mining call and AutoTool never sees
     * the block. This holds the tool the same way the single block miner does.
     */
    private void holdTool(BlockPos pos) {
        if (pos == null) {
            slots.restoreIfMine();
            return;
        }
        // A slot the player picked themselves is left alone.
        if (slots.isHolding() && !slots.stillMine()) {
            slots.forget();
            return;
        }
        ItemUtil.selectBestTool(BlockUtil.state(pos), slots);
    }

    private BlockPos firstWanted(List<BlockPos> scan) {
        for (BlockPos pos : scan) {
            if (wanted(pos)) {
                return pos;
            }
        }
        return null;
    }

    private BlockPos nearest(List<BlockPos> scan) {
        BlockPos best = null;
        int bestTicks = Integer.MAX_VALUE;
        for (BlockPos pos : scan) {
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
        if (mode.is(Mode.LIST)
            && blocks.contains(state.getBlock()) != listMode.is(ListMode.WHITELIST)) {
            return false;
        }
        if (smash.isOn() && state.getDestroySpeed(mc.level, pos) != 0) {
            return false;
        }
        if (flat.isOn() && pos.getY() + 0.5 < mc.player.getY()) {
            return false;
        }
        if (BlockUtil.isStandingOn(pos)) {
            return false;
        }
        double distance = BlockUtil.distanceTo(pos);
        if (distance > range.getValue()) {
            return false;
        }
        return distance <= wallsRange.getValue() || hasLineOfSight(pos);
    }

    private boolean hasLineOfSight(BlockPos pos) {
        Boolean cached = sightCache.get(pos);
        if (cached != null) {
            return cached;
        }
        Vec3 eye = mc.player.getEyePosition();
        Vec3 point = BlockUtil.hitPoint(pos, BlockUtil.facingSide(pos));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, point,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        // A target with no collider lets the ray run all the way to the end point.
        boolean result = hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
        sightCache.put(pos, result);
        return result;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        int strength = (int) (255 * highlightStrength.getValue() / 100);
        if (strength > 0) {
            int argb = ColorUtil.withAlpha(highlight.getColor(), strength);
            for (BlockPos candidate : highlights) {
                event.getBatch().outlineBox(new AABB(candidate).deflate(0.01), argb, false);
            }
        }
        BlockPos pos = speed.is(Speed.LEGIT) ? current : slowPending;
        if (pos == null || !inGame() || BlockUtil.state(pos).isAir()) {
            return;
        }
        event.getBatch().outlineBox(DrawBatch.blockBox(pos),
            ColorUtil.withAlpha(highlight.getColor(), 255), false);
    }
}
