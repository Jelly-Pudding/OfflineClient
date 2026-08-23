package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a block under the player's feet. Targets come from the current
 * position and where the speed puts the player over the next few ticks.
 */
public final class Scaffold extends Module {

    private static final Direction[] BRIDGE_SIDES = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final int MAX_PLACES_PER_TICK = 2;

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks allowed under your feet. Any building block works when this is empty.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.NETHERRACK,
            Blocks.DIRT, Blocks.STONE, Blocks.DEEPSLATE, Blocks.OBSIDIAN));
    private final BoolSetting tower = new BoolSetting("Tower",
        "Hold jump to build straight up.", true);
    private final NumberSetting towerSpeed = new NumberSetting("Tower speed",
        "How hard each tower jump pushes you up.", 0.42, 0.3, 0.5, 0.01)
        .min(0.1).max(1).visibleWhen(tower::isOn);
    private final NumberSetting lookAhead = new NumberSetting("Look ahead",
        "Ticks of movement to build ahead of you.", 2, 0, 5, 1, " ticks").min(0).max(10);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn toward each block on the server side.", true);
    private final BoolSetting swapBack = new BoolSetting("Swap back",
        "Returns to the slot you had after every placement.", true);
    private final BoolSetting onlyOnClick = new BoolSetting("Only on click",
        "Only place whilst you hold the use key down.", false);
    private final BoolSetting down = new BoolSetting("Down",
        "Sneak to build one level lower. When off sneaking pauses Scaffold.", true);

    public Scaffold() {
        super("Scaffold", "Places blocks under you as you walk.", Category.WORLD);
        addSettings(blocks, tower, towerSpeed, lookAhead, rotate, swapBack,
            onlyOnClick, down);
        searchTags("bridge", "auto bridge", "tower");
    }

    private boolean descending;

    private boolean rotatedThisTick;

    // PlayerMixin lifts the sneak edge clamp whilst this is true.
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
        rotatedThisTick = false;
        if (!inGame() || mc.player.isSpectator() || mc.player.isPassenger()) {
            return;
        }
        // The input object is swapped for a silent one by Freecam.
        Input keys = mc.player.input.keyPresses;
        boolean jumping = keys.jump();
        boolean sneaking = keys.shift();

        Vec3 pos = mc.player.position();
        Vec3 velocity = mc.player.getDeltaMovement();

        if (sneaking && !jumping && !down.isOn()) {
            return;
        }
        if (onlyOnClick.isOn() && !mc.options.keyUse.isDown()) {
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
            if (!BlockUtil.isReplaceable(target) || occupied(target)) {
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

    private List<BlockPos> targets(Vec3 pos, Vec3 velocity, int y) {
        int ahead = lookAhead.getInt();
        List<BlockPos> result = new ArrayList<>(ahead + 1);
        for (int ticksAhead = 0; ticksAhead <= ahead; ticksAhead++) {
            double x = pos.x + velocity.x * ticksAhead;
            double z = pos.z + velocity.z * ticksAhead;
            BlockPos target = new BlockPos(Mth.floor(x), y, Mth.floor(z));
            if (!result.contains(target)) {
                result.add(target);
            }
        }
        return result;
    }

    // When nothing solid touches the target a supported neighbour is filled first.
    private boolean place(BlockPos target) {
        Direction support = BlockUtil.findPlaceSupport(target);
        if (support == null) {
            for (Direction side : BRIDGE_SIDES) {
                BlockPos helper = target.relative(side);
                if (!BlockUtil.isReplaceable(helper) || occupied(helper)) {
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
        int slot = BlockUtil.findBlockSlot(block -> allowed(block, finalTarget));
        if (slot == -1) {
            return false;
        }

        int previous = mc.player.getInventory().getSelectedSlot();
        if (slot != previous) {
            mc.player.getInventory().setSelectedSlot(slot);
        }
        // Only the first block of a tick turns. Several look packets in one
        // tick look obviously wrong to the server.
        boolean turn = rotate.isOn() && !rotatedThisTick;
        rotatedThisTick |= turn;
        boolean placed = BlockUtil.place(target, support, turn, true);
        if (swapBack.isOn() && slot != previous) {
            mc.player.getInventory().setSelectedSlot(previous);
        }
        return placed;
    }

    // An empty list falls back to any plain building block.
    // Any entity standing in the square gets the placement refused by the server.
    private boolean occupied(BlockPos pos) {
        if (BlockUtil.intersectsPlayer(pos)) {
            return true;
        }
        return !mc.level.isUnobstructed(Blocks.STONE.defaultBlockState(), pos,
            CollisionContext.empty());
    }

    // A listed block still has to be something worth standing on.
    private boolean allowed(Block block, BlockPos target) {
        if (!BlockUtil.isBuildingBlock(block, target)) {
            return false;
        }
        return blocks.size() == 0 || blocks.contains(block);
    }

    private void towerUp(Vec3 velocity) {
        if (mc.player.getAbilities().flying) {
            return;
        }
        if (mc.player.onGround()) {
            mc.player.setDeltaMovement(velocity.x, towerSpeed.getValue(), velocity.z);
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
