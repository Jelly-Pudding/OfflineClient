package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AutoTrap extends Module {

    public enum Top { FULL, TOP, FACE, NONE }

    public enum Bottom { PLATFORM, SINGLE, FULL, NONE }

    // A corner sitting exactly on a block edge belongs to the block before it.
    private static final double EDGE = 1.0E-5;

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 4, 1, 10, 0.5, " blocks");
    private final EnumSetting<TargetPriority> priority = TargetPriority.setting("Traps",
        TargetPriority.LOW_HEALTH);
    private final NumberSetting placeRange = new NumberSetting("Place range",
        "How far you can reach to place.", 4.5, 1, 6, 0.1, " blocks");
    private final NumberSetting wallsRange = new NumberSetting("Walls range",
        "Shorter reach for a spot you cannot see.", 4, 0, 6, 0.1, " blocks");
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "Blocks the trap may be built from.", BuiltInRegistries.BLOCK,
        List.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN));
    private final EnumSetting<Top> top = new EnumSetting<>("Top",
        "Which blocks go round their head.", Top.FULL)
        .describe(Top.FULL, "Seals their head and the four sides at head height.")
        .describe(Top.TOP, "Covers their head only.")
        .describe(Top.FACE, "Seals the four sides at head height and leaves the top open.")
        .describe(Top.NONE, "Places nothing at head height.");
    private final EnumSetting<Bottom> bottom = new EnumSetting<>("Bottom",
        "Which blocks go under and round their feet.", Bottom.PLATFORM)
        .describe(Bottom.PLATFORM, "The block under their feet and the four beside it.")
        .describe(Bottom.SINGLE, "The block under their feet only.")
        .describe(Bottom.FULL, "The block under their feet and the four sides at feet height.")
        .describe(Bottom.NONE, "Places nothing at their feet.");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between placing rounds.", 1, 0, 5, 1, " ticks");
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many blocks to place in one round.", 2, 1, 4, 1);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards each block.", true);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off when done",
        "Turn off once the target is boxed in.", false);
    private final BoolSetting render = new BoolSetting("Show blocks",
        "Outline the spots still to fill.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 280f).under(render);
    private final BoxStyle nextStyle = new BoxStyle("Next", BoxStyle.Shape.BOTH, 205f).under(render);

    private int timer;
    private final SlotSwap slots = new SlotSwap();
    private String targetName;
    private boolean placed;

    // The spots the last tick found. The unobstructed test walks the entity list.
    private List<BlockPos> pending = List.of();

    public AutoTrap() {
        super("AutoTrap", "Places blocks around an enemy to trap them.", Category.COMBAT);
        addSettings(targetRange, priority, placeRange, wallsRange, blocks, top, bottom, delay,
            perTick, rotate, toggleOff, render);
        addSettings(style.settings());
        addSettings(nextStyle.settings());
        searchTags("obsidian", "trap", "box");
    }

    @Override
    public String getSuffix() {
        return targetName;
    }

    @Override
    protected void onEnable() {
        timer = 0;
        slots.forget();
        targetName = null;
        placed = false;
        pending = List.of();
    }

    @Override
    protected void onDisable() {
        slots.restore();
        targetName = null;
        pending = List.of();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        pending = List.of();
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        Player target = EntityUtil.bestEnemy(targetRange.getValue(), priority.getValue());
        targetName = EntityUtil.nameOf(target);
        if (target == null) {
            slots.restore();
            return;
        }

        List<BlockPos> missing = missingSpots(target);
        pending = missing;
        if (missing.isEmpty()) {
            slots.restore();
            if (toggleOff.isOn() && placed) {
                setEnabled(false);
            }
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        int slot = BlockUtil.findRankedBlockSlot(blocks.getValue(), block -> true);
        if (slot == -1) {
            slots.restore();
            return;
        }
        slots.select(slot);

        int done = 0;
        boolean rotated = false;
        for (BlockPos pos : missing) {
            if (done >= perTick.getInt()) {
                break;
            }
            boolean turn = rotate.isOn() && !rotated;
            rotated |= turn;
            boolean ok = BlockUtil.placeAny(pos, turn, true);
            if (ok) {
                done++;
                placed = true;
            }
        }
        if (done > 0) {
            timer = delay.getInt();
        }
        slots.restore();
    }

    // Furthest first. The near side is left open for as long as possible.
    private List<BlockPos> missingSpots(Player target) {
        Set<BlockPos> spots = new LinkedHashSet<>();
        for (BlockPos feet : columnsUnder(target)) {
            addTop(spots, feet);
            addBottom(spots, feet);
        }
        List<BlockPos> result = new ArrayList<>(spots);
        result.sort(Comparator.comparingDouble(BlockUtil::distanceTo).reversed());
        return result;
    }

    // Every block column the feet overlap. Standing across an edge gives two or four.
    private static Set<BlockPos> columnsUnder(Player target) {
        AABB box = target.getBoundingBox();
        Set<BlockPos> columns = new LinkedHashSet<>();
        columns.add(BlockPos.containing(box.minX, box.minY, box.minZ));
        columns.add(BlockPos.containing(box.minX, box.minY, box.maxZ - EDGE));
        columns.add(BlockPos.containing(box.maxX - EDGE, box.minY, box.minZ));
        columns.add(BlockPos.containing(box.maxX - EDGE, box.minY, box.maxZ - EDGE));
        return columns;
    }

    private void addTop(Set<BlockPos> spots, BlockPos feet) {
        if (top.isAny(Top.FULL, Top.TOP)) {
            addOpen(spots, feet.above(2));
        }
        if (top.isAny(Top.FULL, Top.FACE)) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                addOpen(spots, feet.above().relative(side));
            }
        }
    }

    private void addBottom(Set<BlockPos> spots, BlockPos feet) {
        if (bottom.is(Bottom.NONE)) {
            return;
        }
        addOpen(spots, feet.below());
        if (bottom.is(Bottom.PLATFORM)) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                addOpen(spots, feet.below().relative(side));
            }
        } else if (bottom.is(Bottom.FULL)) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                addOpen(spots, feet.relative(side));
            }
        }
    }

    // A spot only counts when a full block would fit in it and it is within reach.
    private void addOpen(Set<BlockPos> spots, BlockPos pos) {
        if (!BlockUtil.isReplaceable(pos) || !mc.level.isUnobstructed(
            Blocks.OBSIDIAN.defaultBlockState(), pos, CollisionContext.empty())) {
            return;
        }
        if (inReach(pos)) {
            spots.add(pos);
        }
    }

    private boolean inReach(BlockPos pos) {
        double reach = BlockUtil.canSee(Vec3.atCenterOf(pos)) ? placeRange.getValue() : wallsRange.getValue();
        return BlockUtil.distanceTo(pos) <= reach;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        for (int i = 0; i < pending.size(); i++) {
            BoxStyle chosen = i < perTick.getInt() ? nextStyle : style;
            chosen.draw(event.getBatch(), pending.get(i), false);
        }
    }
}
