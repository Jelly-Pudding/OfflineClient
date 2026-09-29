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
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// A piston cannot fire through obsidian and a crystal needs an empty block to stand in.
// Pistons aimed into the hole go first and then the crystal spots that would hurt most.
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
    private final NumberSetting placeRange = new NumberSetting("Place range",
        "How far you can reach to place.", 4.5, 1, 6, 0.1, " blocks");
    private final NumberSetting perRound = new NumberSetting("Blocks per round",
        "How many blocks to place in one round.", 2, 1, 4, 1);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between placing rounds.", 0, 0, 10, 1, " ticks");
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards each block as it goes down.", true);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting render = new BoolSetting("Render",
        "Draws the spots waiting to be filled.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 50).under(render);

    private final SlotSwap slots = new SlotSwap();
    private List<BlockPos> pending = List.of();
    private int timer;

    public Blocker() {
        super("Blocker", "Fills the spots around your hole that crystals and pistons would use.",
            Category.COMBAT);
        addSettings(blocks, onlyInHole, crystals, minDamage, radius, enemyRange, pistons, placeRange,
            perRound, delay, rotate, swing, render);
        addSettings(style.settings());
        searchTags("anti crystal", "anti piston", "face place", "hole");
    }

    @Override
    public String getSuffix() {
        return count(pending.size());
    }

    @Override
    protected void onEnable() {
        timer = 0;
        slots.forget();
        pending = List.of();
    }

    @Override
    protected void onDisable() {
        slots.restore();
        pending = List.of();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        pending = List.of();
        if (!inGame() || mc.player.isSpectator() || mc.player.isDeadOrDying()) {
            return;
        }
        if (onlyInHole.isOn() && !BlockUtil.playerInHole()) {
            slots.restore();
            return;
        }
        Set<BlockPos> spots = new LinkedHashSet<>();
        if (pistons.isOn()) {
            pistonSpots(spots);
        }
        if (crystals.isOn() && enemyNear()) {
            crystalSpots(spots);
        }
        pending = new ArrayList<>(spots);
        if (pending.isEmpty()) {
            slots.restore();
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
        int placed = 0;
        boolean turned = false;
        for (BlockPos pos : pending) {
            if (placed >= perRound.getInt()) {
                break;
            }
            // The rotation manager keeps the first turn of a tick.
            boolean turn = rotate.isOn() && !turned;
            if (BlockUtil.placeAny(pos, turn, false)) {
                swing.getValue().swing(InteractionHand.MAIN_HAND);
                turned |= turn;
                placed++;
            }
        }
        if (placed > 0) {
            timer = delay.getInt();
        }
        slots.restore();
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

    // Every empty block on obsidian or bedrock around the hole where a crystal would
    // hurt at least the minimum. The worst go first.
    private void crystalSpots(Set<BlockPos> spots) {
        BlockPos feet = mc.player.blockPosition();
        int r = radius.getInt();
        List<Spot> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, LOWEST_SPOT, -r),
            feet.offset(r, HIGHEST_SPOT, r))) {
            if (!BlockUtil.crystalBase(BlockUtil.state(pos.below()))
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
    }
}
