package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a block under the player's feet. Targets come from the current
 * position and where the speed puts the player over the next two ticks.
 */
public final class Scaffold extends Module {

    private static final Direction[] BRIDGE_SIDES = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final double JUMP_SPEED = 0.42;
    private static final int MAX_PLACES_PER_TICK = 2;

    private final BoolSetting tower = new BoolSetting("Tower",
        "Hold jump to build straight up much faster than jumping normally.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns toward each block on the server side so the placement looks real.", true);
    private final BoolSetting swapBack = new BoolSetting("Swap back",
        "Returns to the slot you had after every placement.", true);
    private final BoolSetting down = new BoolSetting("Down",
        "Sneak to build one level lower. When off sneaking pauses Scaffold so you can drop.", true);

    public Scaffold() {
        super("Scaffold", "Places blocks under you as you walk.", Category.WORLD);
        addSettings(tower, rotate, swapBack, down);
        searchTags("bridge", "auto bridge", "tower");
    }

    private boolean descending;

    /** True whilst building downward. PlayerMixin lifts the sneak edge
     * clamp then. */
    public boolean isDescending() {
        return isEnabled() && descending;
    }

    @Override
    protected void onDisable() {
        descending = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        descending = false;
        if (!inGame() || mc.player.isSpectator() || mc.player.isPassenger()) {
            return;
        }
        // The input object is swapped for a silent one by Freecam.
        Input keys = mc.player.input.keyPresses;
        boolean jumping = keys.jump();
        boolean sneaking = keys.shift();

        Vec3 pos = mc.player.position();
        Vec3 velocity = mc.player.getDeltaMovement();

        // Sneaking with Down off places nothing.
        if (sneaking && !jumping && !down.isOn()) {
            return;
        }
        descending = sneaking && !jumping && down.isOn();

        int targetY = Mth.floor(pos.y) - 1;
        if (down.isOn() && sneaking && !jumping) {
            targetY--;
        }

        int placed = 0;
        for (BlockPos target : targets(pos, velocity, targetY)) {
            if (placed >= MAX_PLACES_PER_TICK) {
                break;
            }
            if (!BlockUtil.isReplaceable(target) || BlockUtil.intersectsPlayer(target)) {
                continue;
            }
            if (place(target)) {
                placed++;
            }
        }

        if (tower.isOn() && jumping && !sneaking) {
            towerUp(velocity);
        }
    }

    /**
     * The block under the player plus the blocks under the predicted
     * positions over the next two ticks. Nearest first and no duplicates.
     */
    private List<BlockPos> targets(Vec3 pos, Vec3 velocity, int y) {
        List<BlockPos> result = new ArrayList<>(3);
        for (int ticksAhead = 0; ticksAhead <= 2; ticksAhead++) {
            double x = pos.x + velocity.x * ticksAhead;
            double z = pos.z + velocity.z * ticksAhead;
            BlockPos target = new BlockPos(Mth.floor(x), y, Mth.floor(z));
            if (!result.contains(target)) {
                result.add(target);
            }
        }
        return result;
    }

    /**
     * Places a block at the target. When nothing solid touches the target
     * a supported neighbor is filled first.
     */
    private boolean place(BlockPos target) {
        Direction support = BlockUtil.findPlaceSupport(target);
        if (support == null) {
            for (Direction side : BRIDGE_SIDES) {
                BlockPos helper = target.relative(side);
                if (!BlockUtil.isReplaceable(helper) || BlockUtil.intersectsPlayer(helper)) {
                    continue;
                }
                Direction helperSupport = BlockUtil.findPlaceSupport(helper);
                if (helperSupport != null) {
                    target = helper;
                    support = helperSupport;
                    break;
                }
            }
            if (support == null) {
                return false;
            }
        }

        BlockPos finalTarget = target;
        int slot = BlockUtil.findBlockSlot(block -> BlockUtil.isBuildingBlock(block, finalTarget));
        if (slot == -1) {
            return false;
        }

        int previous = mc.player.getInventory().getSelectedSlot();
        if (slot != previous) {
            mc.player.getInventory().setSelectedSlot(slot);
        }
        boolean placed = BlockUtil.place(target, support, rotate.isOn(), true);
        if (swapBack.isOn() && slot != previous) {
            mc.player.getInventory().setSelectedSlot(previous);
        }
        return placed;
    }

    /**
     * Jumps the moment the player touches down and cuts the jump short as
     * soon as the block below is placed.
     */
    private void towerUp(Vec3 velocity) {
        if (mc.player.getAbilities().flying) {
            return;
        }
        if (mc.player.onGround()) {
            mc.player.setDeltaMovement(velocity.x, JUMP_SPEED, velocity.z);
            return;
        }
        if (velocity.y > 0) {
            BlockPos justBelow = BlockPos.containing(
                mc.player.getX(), mc.player.getY() - 0.01, mc.player.getZ());
            if (BlockUtil.isSolid(justBelow)) {
                mc.player.setDeltaMovement(velocity.x, 0, velocity.z);
            }
        }
    }
}
