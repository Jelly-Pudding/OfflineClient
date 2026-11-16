package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.CrystalGuard;
import com.jellypudding.offlineclient.util.Crystals;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.Holes;
import com.jellypudding.offlineclient.util.PlacingRounds;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// A piston cannot fire through obsidian and a crystal needs an empty block to stand in.
// The spots you keep go first then pistons aimed into the hole then the blocks round a
// side somebody is mining and then the crystal spots that would hurt most.
public final class Blocker extends Module {

    // Crystal spots are looked for from one below your feet to two above.
    private static final int LOWEST_SPOT = -1;
    private static final int HIGHEST_SPOT = 2;

    // How far from your block a piston is still watched.
    private static final int PISTON_REACH = 3;

    private record Spot(BlockPos pos, float damage) {
    }

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "Blocks to fill with in order of preference.", BuiltInRegistries.BLOCK,
        List.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN));
    private final BoolSetting onlyInHole = new BoolSetting("Only in holes",
        "Only defends whilst you stand in a blast proof hole.", true);
    private final BoolSetting crystals = new BoolSetting("Crystal spots",
        "Fills every spot around your hole where an enemy crystal would hurt you.", true);
    private final NumberSetting minDamage = new NumberSetting("Min damage",
        "Only fills a spot where a crystal would deal you at least this much after your armour.",
        1, 0, 20, 0.5)
        .under(crystals);
    private final NumberSetting radius = new NumberSetting("Radius",
        "How many blocks out from your hole crystal spots are filled.", 2, 1, 4, 1, " blocks")
        .under(crystals);
    private final NumberSetting enemyRange = new NumberSetting("Enemy range",
        "Only fills crystal spots whilst an enemy is this close. Zero fills them at all times.",
        10, 0, 20, 0.5, " blocks").under(crystals);
    private final BoolSetting pistons = new BoolSetting("Pistons",
        "Puts a block in front of any piston aimed into your hole.", true);
    private final BoolSetting mined = new BoolSetting("Mined blocks",
        "Walls in a block of your surround or trap that someone else is mining and hits a crystal put"
            + " beside it.", true);
    private final NumberSetting mineTime = new NumberSetting("Mine time",
        "Ticks a block has to be mined at before it is walled in.", 10, 0, 60, 1, " ticks")
        .under(mined);
    private final NumberSetting placeRange = new NumberSetting("Place range",
        "How far you can reach to place.", 4.5, 1, 6, 0.1, " blocks");
    private final PlacingRounds rounds = new PlacingRounds(0);
    private final EnumSetting<SwingMode> swing = SwingMode.settingWithUses(SwingMode.BOTH);
    private final KeybindSetting keepKey = new KeybindSetting("Keep spot key",
        "Marks the block you look at and fills it again the moment it breaks.", KeybindSetting.UNBOUND);
    private final ActionSetting clearSpots = new ActionSetting("Clear spots",
        "Forgets every spot you marked.", () -> Tally.cleared(clearKept(), "spot"));
    private final BoolSetting render = new BoolSetting("Render",
        "Draws the spots waiting to be filled.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 50).under(render);

    private List<BlockPos> pending = List.of();
    // The spots you marked with the keep key. They belong to the world they were marked in.
    private final Set<BlockPos> kept = new LinkedHashSet<>();
    private final WorldWatch world = new WorldWatch();
    private final CrystalGuard guard = new CrystalGuard();
    // The tick each block of the cover was first seen mined by somebody else.
    private final Map<BlockPos, Integer> minedSince = new HashMap<>();

    public Blocker() {
        super("Blocker", "Fills the spots around your hole that crystals and pistons would use.",
            Category.COMBAT);
        addSettings(blocks, onlyInHole, crystals, minDamage, radius, enemyRange, pistons, mined, mineTime,
            keepKey, clearSpots, placeRange, rounds.perRound(), rounds.delay(), rounds.rotate(), swing, render);
        addSettings(style.settings());
        searchTags("anti crystal", "anti piston", "anti city", "reinforce", "face place", "hole");
    }

    @Override
    public String getSuffix() {
        return count(pending.size());
    }

    @Override
    protected void onEnable() {
        rounds.forget();
        pending = List.of();
        minedSince.clear();
        guard.forget();
    }

    @Override
    protected void onDisable() {
        rounds.restore();
        pending = List.of();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        pending = List.of();
        if (Hands.tied()) {
            return;
        }
        syncWorld();
        Set<BlockPos> spots = new LinkedHashSet<>();
        for (BlockPos pos : kept) {
            addFillable(spots, pos);
        }
        // Kept spots are filled wherever you stand. The rest wait for a hole whilst Only in holes is on.
        if (!onlyInHole.isOn() || Holes.playerInHole()) {
            if (pistons.isOn()) {
                pistonSpots(spots);
            }
            if (mined.isOn()) {
                minedSpots(spots);
            }
            if (crystals.isOn() && enemyNear()) {
                crystalSpots(spots);
            }
        }
        pending = new ArrayList<>(spots);
        rounds.place(pending, blocks, swing.getValue());
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (!event.presses(keepKey) || !inGame() || mc.gui.screen() != null) {
            return;
        }
        BlockHitResult hit = BlockUtil.aimedBlock();
        if (hit == null) {
            return;
        }
        BlockPos pos = hit.getBlockPos().immutable();
        syncWorld();
        if (kept.remove(pos)) {
            tell("forgot the spot at §f" + BlockUtil.text(pos) + "§7.");
        } else {
            kept.add(pos);
            tell("keeps the spot at §f" + BlockUtil.text(pos) + "§7 filled.");
        }
    }

    // Spots marked in another world point at nothing here.
    private void syncWorld() {
        if (world.changed()) {
            kept.clear();
        }
    }

    private int clearKept() {
        int count = kept.size();
        kept.clear();
        return count;
    }

    private boolean enemyNear() {
        return enemyRange.getValue() == 0 || EntityUtil.nearestEnemy(enemyRange.getValue()) != null;
    }

    // A piston whose head would strike your body or shove something into it. The block
    // in front stops it cold. When the head would strike you directly the block goes
    // where you would be shoved instead and your feet stop the push.
    private void pistonSpots(Set<BlockPos> spots) {
        BlockPos feet = mc.player.blockPosition();
        BlockPos head = feet.above();
        for (BlockPos pos : BlockUtil.positionsAround(feet, PISTON_REACH)) {
            BlockState state = BlockUtil.state(pos);
            if (!(state.getBlock() instanceof PistonBaseBlock) || state.getValue(PistonBaseBlock.EXTENDED)) {
                continue;
            }
            Direction facing = state.getValue(PistonBaseBlock.FACING);
            BlockPos front = pos.relative(facing);
            if (front.equals(feet) || front.equals(head)) {
                addFillable(spots, feet.relative(facing));
            } else if (front.relative(facing).equals(feet) || front.relative(facing).equals(head)) {
                addFillable(spots, front);
            }
        }
    }

    // The blocks beside and over and under each block of your cover that somebody else has
    // mined at for long enough. With those filled the gap it leaves is no use to a crystal.
    // A crystal already standing in one of them is hit whilst the wall still holds.
    private void minedSpots(Set<BlockPos> spots) {
        guard.tick();
        Set<BlockPos> cover = cover();
        int now = mc.player.tickCount;
        minedSince.keySet().removeIf(pos -> !cover.contains(pos) || !BlockUtil.minedByOthers(pos));
        for (BlockPos pos : cover) {
            if (BlockUtil.isReplaceable(pos) || !BlockUtil.minedByOthers(pos)) {
                continue;
            }
            int since = minedSince.computeIfAbsent(pos, key -> now);
            // The clock starts again at nought on a respawn.
            if (now < since) {
                minedSince.put(pos, now);
            } else if (now - since >= mineTime.getInt()) {
                wallIn(spots, pos);
            }
        }
    }

    private void wallIn(Set<BlockPos> spots, BlockPos block) {
        for (Direction way : Direction.values()) {
            BlockPos beside = block.relative(way);
            if (!BlockUtil.isReplaceable(beside)) {
                continue;
            }
            guard.clear(new AABB(beside), rounds.rotate().isOn(), swing.getValue());
            addFillable(spots, beside);
        }
    }

    // The ring round your feet and the one round your head and the blocks over and under you.
    private static Set<BlockPos> cover() {
        List<BlockPos> body = Holes.feetBlocks(mc.player);
        Set<BlockPos> cover = new LinkedHashSet<>();
        for (BlockPos side : Holes.ringAround(body)) {
            cover.add(side);
            cover.add(side.above());
        }
        for (BlockPos block : body) {
            cover.add(block.above(2));
            cover.add(block.below());
        }
        return cover;
    }

    // Every empty block on obsidian or bedrock around the hole where a crystal would
    // hurt at least the minimum. The worst go first.
    private void crystalSpots(Set<BlockPos> spots) {
        BlockPos feet = mc.player.blockPosition();
        int r = radius.getInt();
        List<Spot> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, LOWEST_SPOT, -r),
            feet.offset(r, HIGHEST_SPOT, r))) {
            if (!Crystals.crystalBase(BlockUtil.state(pos.below()))
                || !BlockUtil.state(pos).isAir() || !fillable(pos)) {
                continue;
            }
            float damage = ExplosionUtil.crystalDamage(mc.player, Vec3.atBottomCenterOf(pos));
            if (damage >= minDamage.getFloat()) {
                found.add(new Spot(pos.immutable(), damage));
            }
        }
        found.sort(Comparator.comparingDouble(Spot::damage).reversed());
        for (Spot spot : found) {
            spots.add(spot.pos());
        }
    }

    private void addFillable(Set<BlockPos> spots, BlockPos pos) {
        if (fillable(pos)) {
            spots.add(pos.immutable());
        }
    }

    private boolean fillable(BlockPos pos) {
        return BlockUtil.blockFits(pos) && BlockUtil.distanceTo(pos) <= placeRange.getValue();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        style.drawAll(event.getBatch(), pending, false);
        for (BlockPos pos : kept) {
            event.getBatch().outlineBlock(pos, style.lineColor(), false);
        }
    }
}
