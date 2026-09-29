package com.jellypudding.offlineclient.modules.world;

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
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.Ignition;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.LavaPours;
import com.jellypudding.offlineclient.util.UseBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Sets fire to the blocks round you or pours lava beside them. A spot that was lit is
// tried again after a while if its block still stands.
public final class Arson extends Module {

    public enum Method { FIRE, LAVA }

    // A block to burn and the empty space beside it the fire or lava goes into. A fire
    // carries its click and lava carries its pour.
    private record Target(BlockPos block, BlockPos cell, BlockHitResult click, LavaPours.Pour pour) {
    }

    // Ticks before a space that was lit or poured into is tried again.
    private static final int RELIGHT_TICKS = 100;

    // Blocks worked out each tick. The nearest are kept.
    private static final int MAX_TARGETS = 32;

    // Lava pours worked out each tick. The flow test that keeps lava off you is costly.
    private static final int MAX_POUR_TESTS = 16;

    // Fire on top of a block is tried first and under it last.
    private static final List<Direction> CELL_ORDER = List.of(Direction.UP, Direction.NORTH,
        Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN);

    private final EnumSetting<Method> method = new EnumSetting<>("Method",
        "What the blocks are burnt with.", Method.FIRE)
        .describe(Method.FIRE, "Lights fire on them with flint and steel or a fire charge.")
        .describe(Method.LAVA, "Pours lava beside them from a bucket anywhere in your inventory.");
    private final NumberSetting range = new NumberSetting("Range",
        "How far away blocks are burnt.", 4.5, 1, 6, 0.1, " blocks").min(1);
    private final NumberSetting wallsRange = new NumberSetting("Walls range",
        "How far fire is lit with no clear view from your eyes. The server never checks.",
        4.5, 0, 6, 0.1, " blocks").min(0).under(method, Method.FIRE);
    private final NumberSetting perRound = new NumberSetting("Blocks per round",
        "How many blocks are set alight in one round.", 2, 1, 8, 1).min(1);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between rounds.", 2, 0, 20, 1, " ticks").min(0);
    private final BoolSetting flammableOnly = new BoolSetting("Flammable only",
        "Only burns blocks that catch fire such as wood and wool and leaves. Off burns any solid block.", true);
    private final NumberSetting aboveYou = new NumberSetting("Above you",
        "Only burns blocks this many blocks above your feet or higher. Zero burns them at any height.",
        1, 0, 10, 1, " blocks").min(0);
    private final NumberSetting keepAway = new NumberSetting("Keep away",
        "Never starts a fire or a pour closer to you than this.", 2, 0, 6, 0.5, " blocks").min(0);
    private final RegistryListSetting<Block> skip = new RegistryListSetting<>("Skip blocks",
        "Blocks that are never set alight. Click to pick them.", BuiltInRegistries.BLOCK,
        List.of(Blocks.SHORT_GRASS, Blocks.TALL_GRASS));
    private final LavaPours pours = new LavaPours().under(method, Method.LAVA);
    private final Ignition ignition = new Ignition().under(method, Method.FIRE);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards each fire on the server side. Lava always turns since the bucket pours where you look.", true)
        .under(method, Method.FIRE);
    private final BoolSetting render = new BoolSetting("Show targets",
        "Outlines the blocks about to be set alight.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.LINES, 20f).under(render);

    private final HotbarLoan loan = new HotbarLoan();
    private final SlotSwap slots = new SlotSwap();
    private final Cooldowns<BlockPos> tried = new Cooldowns<>();
    private final ChatWarning warning = new ChatWarning();

    // Refilled every tick nearest first. The first few are set alight and all are drawn.
    private final List<Target> targets = new ArrayList<>();

    private int timer;
    private int pourTests;

    public Arson() {
        super("Arson", "Sets fire to the blocks round you or pours lava on them.", Category.WORLD);
        addSettings(method, range, wallsRange, perRound, delay, flammableOnly, aboveYou, keepAway, skip);
        addSettings(pours.settings());
        addSettings(ignition.settings());
        addSettings(rotate, render);
        addSettings(style.settings());
        searchTags("burn everything", "lava everything", "fire", "grief", "burn base", "lava aura");
    }

    @Override
    public String getSuffix() {
        return count(targets.size());
    }

    @Override
    protected void onEnable() {
        timer = 0;
        targets.clear();
        tried.clear();
        pours.start();
        warning.clear();
        loan.forget();
        slots.forget();
    }

    @Override
    protected void onDisable() {
        loan.giveBack();
        slots.restoreIfMine();
        targets.clear();
        pours.stop();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        targets.clear();
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        tried.tick();
        // A scoop takes the tick. Lava poured before a switch to fire is still taken back.
        if (UseBudget.remaining() > 0 && pours.scoopDue(loan)) {
            return;
        }
        String missing = missing();
        if (missing != null) {
            warning.say(missing);
            return;
        }
        warning.clear();
        collect();
        if (timer > 0) {
            timer--;
            return;
        }
        int done = 0;
        for (Target target : targets) {
            if (done >= perRound.getInt() || UseBudget.remaining() == 0) {
                break;
            }
            // Two blocks can share one empty space. It only needs lighting once.
            if (tried.contains(target.cell())) {
                continue;
            }
            if (setAlight(target)) {
                done++;
                tried.put(target.cell(), RELIGHT_TICKS);
            }
        }
        slots.restoreIfMine();
        if (done > 0) {
            timer = delay.getInt();
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        for (Target target : targets) {
            style.draw(event.getBatch(), target.block(), false);
        }
    }

    // What the method needs and the inventory lacks. Null when it is all there.
    private String missing() {
        if (method.is(Method.FIRE)) {
            return ignition.atHand() ? null : Ignition.NO_LIGHTER;
        }
        return LavaPours.carried() ? null : LavaPours.NO_BUCKET;
    }

    private void collect() {
        int lowest = mc.player.blockPosition().getY() + aboveYou.getInt();
        pourTests = 0;
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (targets.size() >= MAX_TARGETS || pourTests >= MAX_POUR_TESTS) {
                return;
            }
            if ((aboveYou.getInt() > 0 && pos.getY() < lowest) || !burnable(pos)) {
                continue;
            }
            Target target = method.is(Method.FIRE) ? fireTarget(pos) : lavaTarget(pos);
            if (target != null) {
                targets.add(target);
            }
        }
    }

    private boolean burnable(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        if (state.isAir() || !state.getFluidState().isEmpty() || skip.contains(state.getBlock())) {
            return false;
        }
        return flammableOnly.isOn() ? state.ignitedByLava() : BlockUtil.isSolid(pos);
    }

    // The fire goes into an empty space beside the block with a click on the block's own face.
    // Beside a block that does not burn it only takes on top.
    private Target fireTarget(BlockPos block) {
        if (!BlockUtil.serverReaches(block)) {
            return null;
        }
        for (Direction side : CELL_ORDER) {
            BlockPos cell = block.relative(side);
            if (!open(cell) || !Ignition.fireFits(cell)) {
                continue;
            }
            if (BlockUtil.distanceTo(cell) <= wallsRange.getValue() || BlockUtil.canSee(cell)) {
                return new Target(block, cell, Ignition.fireClick(cell, side.getOpposite()), null);
            }
        }
        return null;
    }

    private Target lavaTarget(BlockPos block) {
        for (Direction side : CELL_ORDER) {
            BlockPos cell = block.relative(side);
            if (!open(cell) || !LavaPours.takesLava(cell)) {
                continue;
            }
            pourTests++;
            LavaPours.Pour pour = pours.plan(cell);
            if (pour != null) {
                return new Target(block, cell, null, pour);
            }
        }
        return null;
    }

    // A space not tried lately that sits clear of you.
    private boolean open(BlockPos cell) {
        return !tried.contains(cell)
            && mc.player.position().distanceTo(Vec3.atBottomCenterOf(cell)) >= keepAway.getValue();
    }

    private boolean setAlight(Target target) {
        if (target.pour() != null) {
            return pours.pour(loan, target.pour());
        }
        return ignition.use(slots, hand -> Ignition.strike(target.click(), hand, rotate.isOn()));
    }
}
