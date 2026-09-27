package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.MovementUtil;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

// Opens doors and fence gates and trapdoors that stand in the way you are walking and
// shuts them again once you are through. Only what the module opened is shut.
public final class AutoDoors extends Module {

    // How far past your body the way ahead reaches.
    private static final double LOOK_AHEAD = 1;

    // Ticks a door is left alone after each click. Nothing flaps open and shut.
    private static final int CLICK_COOLDOWN = 10;

    // Your body grown by this much has to be clear of a door before it shuts.
    private static final double CLEARANCE = 0.5;

    private final BoolSetting doors = new BoolSetting("Doors",
        "Opens doors. Iron doors need redstone and are left alone.", true);
    private final BoolSetting gates = new BoolSetting("Fence gates",
        "Opens fence gates.", true);
    private final BoolSetting trapdoors = new BoolSetting("Trapdoors",
        "Opens or shuts a trapdoor that blocks your way. One you can step onto is left alone.", true);
    private final BoolSetting closeBehind = new BoolSetting("Close behind",
        "Shuts everything it opened once you are through.", true);
    private final EnumSetting<FaceMode> face = FaceMode.setting(FaceMode.SERVER);

    // Whether each block the module moved was open before. The lower half stands for a whole door.
    private final Map<BlockPos, Boolean> moved = new HashMap<>();
    private final Cooldowns<BlockPos> clicked = new Cooldowns<>();
    private final WorldWatch world = new WorldWatch();

    public AutoDoors() {
        super("AutoDoors", "Opens doors and gates in your way and shuts them behind you.",
            Category.PLAYER);
        addSettings(doors, gates, trapdoors, closeBehind, face);
        searchTags("door", "gate", "trapdoor", "open");
    }

    @Override
    protected void onEnable() {
        moved.clear();
        clicked.clear();
        world.accept();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        boolean respawned = clicked.tick();
        if (world.changed() || respawned) {
            moved.clear();
        }
        if (mc.gui.screen() != null) {
            return;
        }
        Vec3 heading = MovementUtil.inputDirection();
        AABB path = heading == Vec3.ZERO ? null : path(heading);
        if (path != null && openAhead(path, heading)) {
            return;
        }
        if (closeBehind.isOn()) {
            shutBehind(path);
        } else {
            moved.clear();
        }
    }

    // The space your body sweeps through over the next step. Whatever is low enough to
    // step onto is left out.
    private AABB path(Vec3 heading) {
        AABB box = mc.player.getBoundingBox();
        AABB body = new AABB(box.minX, box.minY + mc.player.maxUpStep(), box.minZ, box.maxX, box.maxY, box.maxZ);
        return body.expandTowards(heading.x * LOOK_AHEAD, 0, heading.z * LOOK_AHEAD);
    }

    // Clicks the first block in the way that would clear it by opening or shutting.
    private boolean openAhead(AABB path, Vec3 heading) {
        AABB middle = middleOf(path, heading);
        for (BlockPos pos : BlockPos.betweenClosed(path)) {
            BlockState state = BlockUtil.state(pos);
            if (!handles(state)) {
                continue;
            }
            BlockPos key = key(pos, state);
            if (clicked.contains(key) || !crosses(state, pos, path)
                || crosses(state.cycle(BlockStateProperties.OPEN), pos, middle)) {
                continue;
            }
            moved.putIfAbsent(key, state.getValue(BlockStateProperties.OPEN));
            click(pos.immutable(), key);
            return true;
        }
        return false;
    }

    // Puts one block back the way it was once you are clear of it. A block someone else
    // moved or one left out of reach is forgotten.
    private void shutBehind(AABB path) {
        AABB body = mc.player.getBoundingBox().inflate(CLEARANCE);
        Iterator<Map.Entry<BlockPos, Boolean>> entries = moved.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<BlockPos, Boolean> entry = entries.next();
            BlockPos pos = entry.getKey();
            BlockState state = BlockUtil.state(pos);
            if (!handles(state) || state.getValue(BlockStateProperties.OPEN) == entry.getValue()
                || !BlockUtil.inReach(pos)) {
                entries.remove();
                continue;
            }
            if (clicked.contains(pos) || inside(body, pos, state)
                || path != null && crosses(state.cycle(BlockStateProperties.OPEN), pos, path)) {
                continue;
            }
            click(pos, pos);
            entries.remove();
            return;
        }
    }

    // The line the middle of your body walks along. The leaf of an open door or trapdoor lies
    // along one side of its block and in a doorway never reaches it. Opening is still worth it
    // when the leaf would only catch the edge of your body.
    private AABB middleOf(AABB path, Vec3 heading) {
        double half = mc.player.getBbWidth() / 2;
        return path.deflate(half * Math.abs(heading.z), 0, half * Math.abs(heading.x));
    }

    private void click(BlockPos pos, BlockPos key) {
        clicked.put(key, CLICK_COOLDOWN);
        BlockUtil.interact(pos, face.getValue());
    }

    private boolean handles(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock door) {
            return doors.isOn() && door.type().canOpenByHand();
        }
        if (block instanceof FenceGateBlock) {
            return gates.isOn();
        }
        return block instanceof TrapDoorBlock trapdoor && trapdoors.isOn() && trapdoor.getType().canOpenByHand();
    }

    // Both halves of a door open together. The lower one names it.
    private static BlockPos key(BlockPos pos, BlockState state) {
        boolean upper = state.hasProperty(DoorBlock.HALF) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
        return upper ? pos.below().immutable() : pos.immutable();
    }

    private boolean inside(AABB body, BlockPos pos, BlockState state) {
        boolean door = state.getBlock() instanceof DoorBlock;
        return body.intersects(new AABB(pos)) || door && body.intersects(new AABB(pos.above()));
    }

    private boolean crosses(BlockState state, BlockPos pos, AABB path) {
        for (AABB part : state.getCollisionShape(mc.level, pos, CollisionContext.of(mc.player)).toAabbs()) {
            if (part.move(pos).intersects(path)) {
                return true;
            }
        }
        return false;
    }
}
