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
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.Holes;
import com.jellypudding.offlineclient.util.PlacingRounds;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AutoTrap extends Module {

    public enum Top { FULL, TOP, FACE, NONE }

    public enum Bottom { PLATFORM, SINGLE, FULL, NONE }

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
    private final PlacingRounds rounds = new PlacingRounds(1);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off when done",
        "Turns off once the target is boxed in.", false);
    private final BoolSetting render = new BoolSetting("Show blocks",
        "Outlines the spots still to fill.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 280f).under(render);
    private final BoxStyle nextStyle = new BoxStyle("Next", BoxStyle.Shape.BOTH, 205f).under(render);

    private String targetName;
    private boolean placed;

    // The spots the last tick found. The unobstructed test walks the entity list.
    private List<BlockPos> pending = List.of();

    public AutoTrap() {
        super("AutoTrap", "Places blocks around an enemy to trap them.", Category.COMBAT);
        addSettings(targetRange, priority, placeRange, wallsRange, blocks, top, bottom, rounds.delay(),
            rounds.perRound(), rounds.rotate(), toggleOff, render);
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
        rounds.forget();
        targetName = null;
        placed = false;
        pending = List.of();
    }

    @Override
    protected void onDisable() {
        rounds.restore();
        targetName = null;
        pending = List.of();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        pending = List.of();
        if (Hands.tied()) {
            return;
        }
        Player target = EntityUtil.bestEnemy(targetRange.getValue(), priority.getValue());
        targetName = EntityUtil.displayNameOf(target);
        if (target == null) {
            rounds.restore();
            return;
        }

        List<BlockPos> missing = missingSpots(target);
        pending = missing;
        if (missing.isEmpty()) {
            rounds.restore();
            if (toggleOff.isOn() && placed) {
                setEnabled(false);
            }
            return;
        }
        placed |= rounds.place(missing, blocks, SwingMode.BOTH) > 0;
    }

    // Furthest first. The near side is left open for as long as possible.
    private List<BlockPos> missingSpots(Player target) {
        List<BlockPos> feet = Holes.feetBlocks(target);
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos pos : Holes.trapSpots(feet, top.isAny(Top.FULL, Top.TOP),
            top.isAny(Top.FULL, Top.FACE), !bottom.is(Bottom.NONE))) {
            if (inReach(pos)) {
                spots.add(pos);
            }
        }
        if (bottom.is(Bottom.PLATFORM)) {
            for (BlockPos side : Holes.ringAround(feet)) {
                addOpen(spots, side.below());
            }
        } else if (bottom.is(Bottom.FULL)) {
            for (BlockPos side : Holes.ringAround(feet)) {
                addOpen(spots, side);
            }
        }
        spots.sort(Comparator.comparingDouble(BlockUtil::distanceTo).reversed());
        return spots;
    }

    private void addOpen(List<BlockPos> spots, BlockPos pos) {
        if (!BlockUtil.blockFits(pos)) {
            return;
        }
        if (inReach(pos)) {
            spots.add(pos);
        }
    }

    private boolean inReach(BlockPos pos) {
        return BlockUtil.inReach(pos, placeRange.getValue(), wallsRange.getValue());
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        for (int i = 0; i < pending.size(); i++) {
            BoxStyle chosen = i < rounds.perRound().getInt() ? nextStyle : style;
            chosen.draw(event.getBatch(), pending.get(i), false);
        }
    }
}
