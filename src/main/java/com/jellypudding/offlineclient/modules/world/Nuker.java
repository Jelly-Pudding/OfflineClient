package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Legit mode mines one block at a time like a held click.
// Instant mode fires the break packets straight away.
public final class Nuker extends Module {

    public enum Shape { SPHERE, UNIFORM_CUBE, CUBE }

    public enum Mode { ALL, SELECTED, LIST }

    public enum ListMode { WHITELIST, BLACKLIST }

    public enum Speed { LEGIT, INSTANT }

    public enum Order { NEAREST, FURTHEST, FASTEST, TOP_DOWN }

    private static final int RETRY_TICKS = 10;
    private static final int SLOW_GRACE_TICKS = 20;

    private static final int MAX_HIGHLIGHTS = 64;

    // The furthest a cube side may reach out.
    private static final int MAX_SIDE = 6;

    private final EnumSetting<Shape> shape = new EnumSetting<>("Shape",
        "The region searched for blocks.", Shape.SPHERE)
        .describe(Shape.SPHERE, "A ball of the given radius around you.")
        .describe(Shape.UNIFORM_CUBE, "A cube of the rounded range on every side.")
        .describe(Shape.CUBE, "A box with its own reach on each of the six sides.");
    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes to break blocks.",
        4.5, 1, 6, 0.1).min(1).max(6).under(shape, Shape.SPHERE, Shape.UNIFORM_CUBE);
    private final NumberSetting up = sideSetting("Up", "above your feet").under(shape, Shape.CUBE);
    private final NumberSetting down = sideSetting("Down", "below your feet").under(shape, Shape.CUBE);
    private final NumberSetting left = sideSetting("Left", "to your left").under(shape, Shape.CUBE);
    private final NumberSetting right = sideSetting("Right", "to your right").under(shape, Shape.CUBE);
    private final NumberSetting forward = sideSetting("Forward", "in front of you").under(shape, Shape.CUBE);
    private final NumberSetting back = sideSetting("Back", "behind you").under(shape, Shape.CUBE);
    private final NumberSetting wallsRange = new NumberSetting("Walls range",
        "How far to break blocks with no clear view from your eyes.",
        4.5, 0, 6, 0.1).min(0).max(6);
    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Which blocks the module is allowed to break.", Mode.ALL)
        .describe(Mode.ALL, "Every block in range.")
        .describe(Mode.SELECTED, "Only the block type you click.")
        .describe(Mode.LIST, "Whatever the list below allows.");
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
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
    private final KeybindSetting selectBind = new KeybindSetting("Select block bind",
        "Adds or removes the block under your crosshair from the list.", KeybindSetting.UNBOUND)
        .under(mode, Mode.LIST);
    private final BoolSetting lockTarget = new BoolSetting("Lock target",
        "Keeps the block you picked instead of following your next click.", false)
        .under(mode, Mode.SELECTED);
    private final BoolSetting flat = new BoolSetting("Flat",
        "Only breaks blocks at your feet or higher.", false);
    private final BoolSetting smash = new BoolSetting("Smash",
        "Only breaks blocks with no hardness such as plants and torches.", false);
    private final BoolSetting suitableTools = new BoolSetting("Suitable tools only",
        "Skips blocks your held item is the wrong tool for.", false);
    private final BoolSetting interact = new BoolSetting("Interact",
        "Right clicks each matching block once instead of breaking it.", false);
    private final EnumSetting<Speed> speed = new EnumSetting<>("Speed",
        "How the blocks come down.", Speed.LEGIT)
        .describe(Speed.LEGIT, "One block at a time like a held click.")
        .describe(Speed.INSTANT, "Fires the break packets for several at once.")
        .unless(interact);
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many one hit blocks to break each tick in Instant mode.", 4, 1, 16, 1)
        .min(1).under(speed, () -> !interact.isOn() && speed.is(Speed.INSTANT));
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait before starting on a new block.", 0, 0, 20, 1, " ticks").min(0);
    private final BoolSetting autoTool = new BoolSetting("Auto tool",
        "Holds your fastest tool before the break packets go out.", true)
        .under(speed, () -> !interact.isOn() && speed.is(Speed.INSTANT));
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the block on the server side.", true);
    private final EnumSetting<Order> order = new EnumSetting<>("Order",
        "Which block gets dealt with first.", Order.NEAREST)
        .describe(Order.NEAREST, "The closest block first.")
        .describe(Order.FURTHEST, "The furthest block first.")
        .describe(Order.FASTEST, "The quickest block to break first.")
        .describe(Order.TOP_DOWN, "The highest block first.");
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoxStyle blockStyle = new BoxStyle("Block", BoxStyle.Shape.BOTH, 0);
    private final NumberSetting highlightStrength = new NumberSetting("Highlight strength",
        "How strongly the blocks waiting their turn show.", 35, 0, 100, 5, "%").max(100);
    private final BoolSetting boundingBox = new BoolSetting("Bounding box",
        "Draws the cube region you are clearing.", true)
        .under(shape, Shape.UNIFORM_CUBE, Shape.CUBE);
    private final BoxStyle regionStyle = new BoxStyle("Region", BoxStyle.Shape.BOTH, 192)
        .under(boundingBox);

    private Block selected;
    private BlockPos current;
    private final Map<BlockPos, Integer> attempted = new HashMap<>();
    private final Set<BlockPos> interacted = new HashSet<>();
    private BlockPos slowPending;
    private int slowDeadline;
    private int lastTick;
    private BlockPos lastChosen;
    private int waitTicks;

    private final SlotSwap slots = new SlotSwap();

    // Filled once a tick and read by the renderer.
    private final List<BlockPos> candidates = new ArrayList<>();

    // The drawn cube for the two box shapes. Null whilst the shape is a sphere.
    private AABB region;

    // A block is scanned several times a tick and one raycast each covers them all.
    private final Map<BlockPos, Boolean> sightCache = new HashMap<>();

    public Nuker() {
        super("Nuker", "Breaks all blocks around you.", Category.WORLD);
        addSettings(shape, range, up, down, left, right, forward, back, wallsRange,
            mode, blocks, listMode, selectBind, lockTarget, flat, smash, suitableTools,
            interact, speed, perTick, delay, autoTool, rotate, order, swing);
        addSettings(blockStyle.settings());
        addSettings(highlightStrength, boundingBox);
        addSettings(regionStyle.settings());
        searchTags("dig", "excavate", "break blocks");
    }

    private static NumberSetting sideSetting(String name, String where) {
        return new NumberSetting(name, "How many blocks " + where + " to clear.",
            1, 0, MAX_SIDE, 1, " blocks").min(0).max(MAX_SIDE);
    }

    @Override
    public String getSuffix() {
        if (mode.is(Mode.SELECTED)) {
            return selected == null ? "Nothing selected" : BlockUtil.blockName(selected);
        }
        if (mode.is(Mode.LIST)) {
            return listMode.getValueString();
        }
        return shape.is(Shape.CUBE) ? "Cube" : range.getValueString();
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        reset();
        if (mode.is(Mode.SELECTED) && inGame()) {
            selectLookedAtBlock();
        }
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        slots.restoreIfMine();
        reset();
    }

    private void reset() {
        current = null;
        attempted.clear();
        interacted.clear();
        candidates.clear();
        sightCache.clear();
        slowPending = null;
        lastChosen = null;
        waitTicks = 0;
        lastTick = 0;
        region = null;
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
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() != GLFW.GLFW_PRESS || mc.gui.screen() != null) {
            return;
        }
        if (!selectBind.isBound() || event.getKey() != selectBind.getValue()) {
            return;
        }
        if (!inGame() || !mode.is(Mode.LIST)) {
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        Block block = BlockUtil.state(hit.getBlockPos()).getBlock();
        String list = listMode.getValueString().toLowerCase(Locale.ROOT);
        if (blocks.contains(block)) {
            blocks.remove(block);
            ChatUtil.message("§bNuker §7took §f" + BlockUtil.blockName(block)
                + "§7 off the " + list + ".");
        } else {
            blocks.add(block);
            ChatUtil.message("§bNuker §7added §f" + BlockUtil.blockName(block)
                + "§7 to the " + list + ".");
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        candidates.clear();
        sightCache.clear();
        region = null;
        if (!inGame() || mc.player.isSpectator()) {
            current = null;
            slots.restoreIfMine();
            return;
        }
        // Holding attack means the player is mining by hand.
        if (InputUtil.physicallyHeld(mc.options.keyAttack) || mc.player.isUsingItem()) {
            current = null;
            slots.restoreIfMine();
            return;
        }
        if (mode.is(Mode.SELECTED) && selected == null) {
            slots.restoreIfMine();
            return;
        }
        collect();
        sortCandidates();
        if (candidates.isEmpty()) {
            interacted.clear();
            waitTicks = 0;
            lastChosen = null;
            slots.restoreIfMine();
            return;
        }
        // A fresh target waits out the delay before anything is sent.
        BlockPos first = candidates.getFirst();
        if (lastChosen != null && !lastChosen.equals(first)) {
            waitTicks = delay.getInt();
        }
        lastChosen = first;
        if (waitTicks > 0) {
            waitTicks--;
            BlockMiner.release();
            return;
        }
        if (interact.isOn()) {
            slots.restoreIfMine();
            interactWith(first);
        } else if (speed.is(Speed.LEGIT)) {
            slots.restoreIfMine();
            mineLegit();
        } else {
            mineInstant();
        }
    }

    // Fills the candidate list with every block the region and the filters allow.
    private void collect() {
        if (shape.is(Shape.SPHERE)) {
            for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
                if (wanted(pos)) {
                    candidates.add(pos);
                }
            }
            return;
        }
        BlockPos centre = mc.player.blockPosition();
        if (shape.is(Shape.UNIFORM_CUBE)) {
            int r = Math.max(0, (int) Math.round(range.getValue()) - 1);
            region = new AABB(centre.offset(-r, -r, -r)).minmax(new AABB(centre.offset(r, r, r)));
            for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
                BlockPos fixed = pos.immutable();
                if (wanted(fixed)) {
                    candidates.add(fixed);
                }
            }
            return;
        }
        Direction facing = mc.player.getDirection();
        Direction side = facing.getClockWise();
        int ahead = forward.getInt();
        int behind = back.getInt();
        int starboard = right.getInt();
        int port = left.getInt();
        BlockPos far = centre.offset(facing.getStepX() * ahead + side.getStepX() * starboard,
            up.getInt(), facing.getStepZ() * ahead + side.getStepZ() * starboard);
        BlockPos near = centre.offset(-facing.getStepX() * behind - side.getStepX() * port,
            -down.getInt(), -facing.getStepZ() * behind - side.getStepZ() * port);
        region = new AABB(near).minmax(new AABB(far));
        for (BlockPos pos : BlockPos.betweenClosed(near, far)) {
            BlockPos fixed = pos.immutable();
            if (wanted(fixed)) {
                candidates.add(fixed);
            }
        }
    }

    private void sortCandidates() {
        switch (order.getValue()) {
            case NEAREST -> candidates.sort(Comparator.comparingDouble(BlockUtil::distanceTo));
            case FURTHEST -> candidates.sort(
                Comparator.comparingDouble((BlockPos pos) -> -BlockUtil.distanceTo(pos)));
            case FASTEST -> candidates.sort(Comparator.comparingInt(BlockUtil::breakTicks));
            case TOP_DOWN -> candidates.sort(Comparator.comparingInt((BlockPos pos) -> -pos.getY()));
        }
    }

    private void interactWith(BlockPos pos) {
        if (BlockUtil.useOn(pos, rotate.isOn(), false)) {
            swing.getValue().swing();
        }
        interacted.add(pos);
    }

    private void mineLegit() {
        if (current != null && !candidates.contains(current)) {
            current = null;
        }
        if (current == null) {
            current = candidates.getFirst();
        }
        if (!BlockMiner.mine(current, rotate.isOn(), swing.getValue())) {
            current = null;
        }
    }

    // One hit blocks get their packets right away. A slower block is only queued
    // when the previous one is gone or its break time has run out.
    private void mineInstant() {
        int now = mc.player.tickCount;
        // The tick count restarts on a respawn.
        if (now < lastTick) {
            attempted.clear();
            slowPending = null;
        }
        lastTick = now;
        attempted.values().removeIf(expiry -> expiry <= now);
        if (slowPending != null && (!candidates.contains(slowPending) || now >= slowDeadline)) {
            slowPending = null;
        }
        if (autoTool.isOn()) {
            // The tool has to be in hand before anything is judged a one hit block.
            holdTool(slowPending != null ? slowPending : candidates.getFirst());
        }

        int sent = 0;
        for (BlockPos pos : candidates) {
            if (sent >= perTick.getInt()) {
                break;
            }
            if (attempted.containsKey(pos)) {
                continue;
            }
            if (rotate.isOn()) {
                BlockUtil.faceVector(Vec3.atCenterOf(pos), RotationPriority.MINE);
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
            swing.getValue().swing();
        }
    }

    // Instant mode never reaches the vanilla mining call and AutoTool never sees
    // the block. This holds the tool the same way the single block miner does.
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

    private boolean wanted(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        if (state.isAir()) {
            return false;
        }
        if (!interact.isOn() && !BlockUtil.isBreakable(pos)) {
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
        if (suitableTools.isOn() && !interact.isOn()
            && !mc.player.getMainHandItem().isCorrectToolForDrops(state)) {
            return false;
        }
        if (interact.isOn() && interacted.contains(pos)) {
            return false;
        }
        if (BlockUtil.isStandingOn(pos)) {
            return false;
        }
        return inReach(pos);
    }

    // The server refuses anything past the interaction range whatever the region says.
    private boolean inReach(BlockPos pos) {
        double distance = BlockUtil.distanceTo(pos);
        if (distance > mc.player.blockInteractionRange() + 1) {
            return false;
        }
        if (shape.is(Shape.SPHERE) && distance > range.getValue()) {
            return false;
        }
        return distance <= wallsRange.getValue() || hasLineOfSight(pos);
    }

    private boolean hasLineOfSight(BlockPos pos) {
        Boolean cached = sightCache.get(pos);
        if (cached != null) {
            return cached;
        }
        boolean result = BlockUtil.canSee(pos);
        sightCache.put(pos, result);
        return result;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (region != null && boundingBox.isOn()) {
            regionStyle.draw(event.getBatch(), region, false);
        }
        float strength = (float) (highlightStrength.getValue() / 100);
        if (strength > 0) {
            int drawn = 0;
            for (BlockPos candidate : candidates) {
                if (drawn++ >= MAX_HIGHLIGHTS) {
                    break;
                }
                blockStyle.drawFading(event.getBatch(), DrawBatch.blockBox(candidate), strength, false);
            }
        }
        BlockPos pos = speed.is(Speed.LEGIT) ? current : slowPending;
        if (pos == null || interact.isOn() || !inGame() || BlockUtil.state(pos).isAir()) {
            return;
        }
        blockStyle.draw(event.getBatch(), DrawBatch.blockBox(pos), false);
    }
}
