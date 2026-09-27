package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.HeldKey;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Puts the next step down in front of you whenever you stand on something. The steps
// follow the way you face and each one is a block higher or lower than the last.
public final class Staircase extends Module {

    public enum Slope { UP, DOWN }

    // A stair clicked in the lower half of its space lands the right way up.
    private static final double LOWER_QUARTER = 0.25;

    // Climbing that gets less than this far across for the whole wait counts as stuck.
    // Jumping on the spot moves you up and down and never across.
    private static final double STUCK_DISTANCE = 0.5;
    private static final int STUCK_TICKS = 40;

    private final EnumSetting<Slope> slope = new EnumSetting<>("Direction",
        "Which way the steps go as you walk forward.", Slope.UP)
        .describe(Slope.UP, "Each step is a block higher than the one you stand on.")
        .describe(Slope.DOWN, "Each step is a block lower. It builds over a drop in front of you.");
    private final BoolSetting mountain = new BoolSetting("Mountain",
        "Walks and jumps for you and keeps climbing the way you face.", false)
        .under(slope, Slope.UP);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks and stairs the steps are made of. An empty list allows any.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.COBBLED_DEEPSLATE,
            Blocks.COBBLED_DEEPSLATE_STAIRS, Blocks.STONE, Blocks.STONE_STAIRS, Blocks.NETHERRACK,
            Blocks.DIRT));
    private final BoolSetting stairsFirst = new BoolSetting("Stairs first",
        "Uses stairs before full blocks. Stairs are walked up without a jump.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Sends a look packet towards each full block. Stairs always turn to set the way they face.", true);

    private final SlotSwap slots = new SlotSwap();
    private final HeldKey forward = new HeldKey(options -> options.keyUp);
    private Vec3 lastPos;
    private int stillTicks;

    public Staircase() {
        super("Staircase", "Builds a staircase up or down as you walk.", Category.WORLD);
        addSettings(slope, mountain, blocks, stairsFirst, rotate);
        searchTags("stairs", "steps", "mountain", "climb");
    }

    @Override
    protected void onEnable() {
        lastPos = null;
        stillTicks = 0;
        slots.forget();
    }

    @Override
    protected void onDisable() {
        stopWalking();
        slots.restoreIfMine();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.player.isPassenger()) {
            stopWalking();
            return;
        }
        boolean climbing = mountain.isOn() && slope.is(Slope.UP);
        if (!climbing) {
            stopWalking();
        }
        if (mc.player.onGround() && !buildStep()) {
            disable("Staircase ran out of blocks.");
            return;
        }
        if (climbing) {
            climb();
        }
    }

    // Puts the step in front down when it is missing. False only when there is none to use.
    private boolean buildStep() {
        Direction facing = mc.player.getDirection();
        BlockPos base = mc.player.getOnPos();
        BlockPos ahead = base.relative(facing);
        BlockPos step = slope.is(Slope.UP) ? ahead.above() : ahead.below();
        if (slope.is(Slope.DOWN) && !BlockUtil.isReplaceable(ahead)) {
            return true;
        }
        if (!BlockUtil.blockFits(step) || BlockUtil.intersectsPlayer(step)) {
            return true;
        }
        int slot = stepSlot(step);
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        if (mc.player.getMainHandItem().getItem() instanceof BlockItem item && item.getBlock() instanceof StairBlock) {
            placeStair(step, facing);
        } else {
            BlockUtil.placeAny(step, rotate.isOn(), true);
        }
        slots.restore();
        return true;
    }

    private int stepSlot(BlockPos step) {
        int stair = BlockUtil.findBlockSlot(block -> block instanceof StairBlock && listed(block));
        int full = BlockUtil.findBlockSlot(block -> BlockUtil.isBuildingBlock(block, step) && listed(block));
        if (stairsFirst.isOn()) {
            return stair != -1 ? stair : full;
        }
        return full != -1 ? full : stair;
    }

    private boolean listed(Block block) {
        return blocks.size() == 0 || blocks.contains(block);
    }

    // The server gives a stair the facing of the yaw it holds when the click lands. The
    // yaw goes out on its own packet first. Going down the stair faces back up the slope.
    private void placeStair(BlockPos step, Direction facing) {
        Direction back = slope.is(Slope.UP) ? facing : facing.getOpposite();
        Direction support = stairSupport(step);
        Vec3 hit = support != null
            ? BlockUtil.hitPoint(step.relative(support), support.getOpposite())
            : Vec3.atBottomCenterOf(step).add(0, LOWER_QUARTER, 0);
        FaceMode.SPAM.faceExact(back.toYRot(), RotationManager.pitchTo(hit), RotationPriority.PLACE);
        if (support != null) {
            BlockUtil.place(step, support, false, true);
        } else {
            BlockUtil.placeDirect(step, hit, false, true);
        }
    }

    // A stair leans on the block below it or beside it. One hung from above lands upside down.
    private static Direction stairSupport(BlockPos step) {
        for (Direction side : BlockUtil.BELOW_THEN_SIDES) {
            BlockPos next = step.relative(side);
            if (BlockUtil.isSolid(next) && !BlockUtil.opensOnClick(BlockUtil.state(next))) {
                return side;
            }
        }
        return null;
    }

    // Holds forward and jumps onto a full block step once you walk into it. Stairs need no jump.
    private void climb() {
        forward.hold();
        if (mc.player.onGround() && mc.player.horizontalCollision) {
            mc.player.jumpFromGround();
        }
        Vec3 pos = mc.player.position();
        if (lastPos == null || pos.subtract(lastPos).horizontalDistance() > STUCK_DISTANCE) {
            lastPos = pos;
            stillTicks = 0;
        } else if (++stillTicks > STUCK_TICKS) {
            disable("Staircase stopped because something blocks the way.");
        }
    }

    private void stopWalking() {
        forward.letGo();
        lastPos = null;
        stillTicks = 0;
    }
}
