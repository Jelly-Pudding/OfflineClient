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
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;

// Builds a wither from four soul blocks in a T and three wither skeleton skulls on top.
// The skulls go last and the wither spawns as the third one lands. The two spaces
// beside the bottom block must be air or the pattern never matches.
public final class AutoWither extends Module {

    public enum Where { FRONT, CROSSHAIR }

    // The soul blocks make the T and the skulls sit on top. An item counts when it places the block.
    private enum Kind {
        SOUL_BLOCK(4),
        SKULL(3);

        private final int needed;

        Kind(int needed) {
            this.needed = needed;
        }

        boolean isBlock(BlockState state) {
            if (this == SKULL) {
                return state.is(Blocks.WITHER_SKELETON_SKULL) || state.is(Blocks.WITHER_SKELETON_WALL_SKULL);
            }
            return state.is(BlockTags.WITHER_SUMMON_BASE_BLOCKS);
        }

        boolean isItem(ItemStack stack) {
            return stack.getItem() instanceof BlockItem item && isBlock(item.getBlock().defaultBlockState());
        }
    }

    private record Part(BlockPos pos, Kind kind) {
    }

    // How far in front of your feet the bottom block goes.
    private static final int FRONT_DISTANCE = 2;

    // Ticks a block may keep failing to go down before the build gives up.
    private static final int PATIENCE = 20;

    private static final String IN_THE_WAY = "Something is in the way of the wither.";
    private static final String CORNERS_TAKEN = "The two spaces beside the bottom block have to be empty.";

    private final EnumSetting<Where> where = new EnumSetting<>("Where",
        "Where the wither is built.", Where.FRONT)
        .describe(Where.FRONT, "Two blocks in front of you on the level you stand on.")
        .describe(Where.CROSSHAIR, "Against the block you look at.");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between blocks.", 1, 0, 10, 1, " ticks").min(0);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards each block.", true);
    private final BoolSetting render = new BoolSetting("Show blocks",
        "Outlines the blocks still to be placed.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 280f).under(render);

    private final HotbarLoan loan = new HotbarLoan();

    // Soul blocks from the bottom up and then the skulls.
    private final List<Part> parts = new ArrayList<>();

    // The two spaces beside the bottom block.
    private final List<BlockPos> corners = new ArrayList<>();

    private int next;
    private int timer;
    private int failures;

    public AutoWither() {
        super("AutoWither", "Builds and spawns a wither.", Category.WORLD);
        addSettings(where, delay, rotate, render);
        addSettings(style.settings());
        searchTags("wither", "boss", "soul sand", "skull");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        return count(parts.size() - next, "left");
    }

    @Override
    protected void onEnable() {
        parts.clear();
        corners.clear();
        next = 0;
        timer = 0;
        failures = 0;
        loan.forget();
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        if (mc.level.getDifficulty() == Difficulty.PEACEFUL) {
            disable("A wither never spawns on peaceful.");
            return;
        }
        if (!carriesEnough()) {
            disable("You need four soul sand or soul soil and three wither skeleton skulls.");
            return;
        }
        BlockPos stem = stem();
        if (stem == null) {
            disable("Look at a block first.");
            return;
        }
        // The T faces you when it fits. Edge on is the fallback.
        Direction facing = mc.player.getDirection();
        String problem = plan(stem, facing.getClockWise());
        if (problem != null && plan(stem, facing) != null) {
            disable(problem);
        }
    }

    @Override
    protected void onDisable() {
        loan.giveBack();
        parts.clear();
        corners.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        if (clearCorner()) {
            return;
        }
        while (next < parts.size()) {
            Part part = parts.get(next);
            if (built(part)) {
                next++;
                continue;
            }
            if (!BlockUtil.blockFits(part.pos())) {
                disable(IN_THE_WAY);
                return;
            }
            if (!place(part)) {
                if (++failures > PATIENCE) {
                    disable("The wither could not be built.");
                }
                return;
            }
            failures = 0;
            next++;
            if (delay.getInt() > 0) {
                timer = delay.getInt();
                return;
            }
        }
        setEnabled(false);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        for (int i = next; i < parts.size(); i++) {
            style.draw(event.getBatch(), parts.get(i).pos(), true);
        }
    }

    private BlockPos stem() {
        if (where.is(Where.FRONT)) {
            return mc.player.blockPosition().relative(mc.player.getDirection(), FRONT_DISTANCE);
        }
        BlockHitResult hit = BlockUtil.aimedBlock();
        return hit == null ? null : BlockUtil.placeSpot(hit);
    }

    // Lays the T out with its arms along the given side. Null once it fits and the
    // reason it does not otherwise.
    private String plan(BlockPos stem, Direction across) {
        BlockPos centre = stem.above();
        BlockPos left = centre.relative(across);
        BlockPos right = centre.relative(across.getOpposite());
        List<Part> planned = List.of(new Part(stem, Kind.SOUL_BLOCK), new Part(centre, Kind.SOUL_BLOCK),
            new Part(left, Kind.SOUL_BLOCK), new Part(right, Kind.SOUL_BLOCK),
            new Part(left.above(), Kind.SKULL), new Part(right.above(), Kind.SKULL),
            new Part(centre.above(), Kind.SKULL));
        List<BlockPos> sides = List.of(stem.relative(across), stem.relative(across.getOpposite()));
        for (Part part : planned) {
            if (built(part)) {
                continue;
            }
            if (!BlockUtil.blockFits(part.pos())) {
                return IN_THE_WAY;
            }
            if (!BlockUtil.inReach(part.pos())) {
                return "The wither would be out of reach.";
            }
        }
        for (BlockPos side : sides) {
            if (!BlockUtil.state(side).isAir() && !clearable(side)) {
                return CORNERS_TAKEN;
            }
        }
        parts.addAll(planned);
        corners.addAll(sides);
        return null;
    }

    // Grass or a flower beside the bottom block breaks in one hit. True whilst one is cleared.
    private boolean clearCorner() {
        for (BlockPos side : corners) {
            if (BlockUtil.state(side).isAir()) {
                continue;
            }
            if (!clearable(side)) {
                disable(CORNERS_TAKEN);
                return true;
            }
            mc.gameMode.startDestroyBlock(side, Direction.UP);
            timer = delay.getInt();
            return true;
        }
        return false;
    }

    // Flowers cannot be built over like grass but still break in one hit.
    private static boolean clearable(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        return (state.canBeReplaced() || state.is(BlockTags.FLOWERS)) && state.getFluidState().isEmpty()
            && BlockUtil.canInstantBreak(pos) && BlockUtil.inReach(pos);
    }

    private static boolean built(Part part) {
        return part.kind().isBlock(BlockUtil.state(part.pos()));
    }

    // A skull goes on the top of the soul block under it and stands upright.
    private boolean place(Part part) {
        int slot = InventoryUtil.findSlot(part.kind()::isItem, InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1 || !loan.select(slot)) {
            return false;
        }
        if (part.kind() == Kind.SKULL) {
            return BlockUtil.place(part.pos(), Direction.DOWN, rotate.isOn(), true);
        }
        return BlockUtil.placeAny(part.pos(), rotate.isOn(), true);
    }

    private static boolean carriesEnough() {
        for (Kind kind : Kind.values()) {
            if (InventoryUtil.count(kind::isItem, InventoryUtil.WHOLE_INVENTORY) < kind.needed) {
                return false;
            }
        }
        return true;
    }
}
