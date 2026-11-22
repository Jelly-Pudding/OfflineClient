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
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MovementUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Switches;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
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

public final class AutoDoors extends Module {

    private static final double LOOK_AHEAD = 1;

    private static final int CLICK_COOLDOWN = 10;

    private static final double CLEARANCE = 0.5;

    private static final int SWITCH_REACH = 2;

    private final BoolSetting doors = new BoolSetting("Doors",
        "Opens the doors a hand can open.", true);
    private final BoolSetting gates = new BoolSetting("Fence gates",
        "Opens fence gates.", true);
    private final BoolSetting trapdoors = new BoolSetting("Trapdoors",
        "Opens or shuts a trapdoor that blocks your way. One you can step onto is left alone.", true);
    private final BoolSetting ironDoors = new BoolSetting("Iron doors",
        "Opens an iron door or trapdoor in your way with the button or lever beside it.", false);
    private final BoolSetting closeBehind = new BoolSetting("Close behind",
        "Shuts everything it opened once you are through. A button lets its door shut by itself.", true);
    private final EnumSetting<FaceMode> face = FaceMode.setting(FaceMode.SERVER);

    // Whether each block the module moved was open before. The lower half stands for a whole door.
    private final Map<BlockPos, Boolean> moved = new HashMap<>();
    private final Cooldowns<BlockPos> clicked = new Cooldowns<>();
    private final WorldWatch world = new WorldWatch();

    public AutoDoors() {
        super("AutoDoors", "Opens doors and gates in your way and shuts them behind you.",
            Category.PLAYER);
        addSettings(doors, gates, trapdoors, ironDoors, closeBehind, face);
        searchTags("door", "gate", "trapdoor", "open", "iron door", "button", "lever");
    }

    @Override
    protected void onEnable() {
        moved.clear();
        clicked.clear();
        world.accept();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (Hands.tied()) {
            return;
        }
        boolean respawned = clicked.tick();
        if (world.changed() || respawned) {
            moved.clear();
        }
        // A door click takes no slot. ShulkerPacker sets down and opens a box with clicks and turns
        // of its own.
        if (mc.gui.screen() != null || Modules.packing()) {
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

    private AABB path(Vec3 heading) {
        AABB box = mc.player.getBoundingBox();
        AABB body = new AABB(box.minX, box.minY + mc.player.maxUpStep(), box.minZ, box.maxX, box.maxY, box.maxZ);
        return body.expandTowards(heading.x * LOOK_AHEAD, 0, heading.z * LOOK_AHEAD);
    }

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
            BlockPos target = clickFor(key, state, false);
            if (target == null) {
                continue;
            }
            moved.putIfAbsent(key, state.getValue(BlockStateProperties.OPEN));
            click(target, key);
            return true;
        }
        return false;
    }

    // A block whose click is still held may not show the server's answer yet and is kept.
    private void shutBehind(AABB path) {
        AABB body = mc.player.getBoundingBox().inflate(CLEARANCE);
        Iterator<Map.Entry<BlockPos, Boolean>> entries = moved.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<BlockPos, Boolean> entry = entries.next();
            BlockPos pos = entry.getKey();
            if (clicked.contains(pos)) {
                continue;
            }
            BlockState state = BlockUtil.state(pos);
            if (!handles(state) || state.getValue(BlockStateProperties.OPEN) == entry.getValue()
                || !BlockUtil.inReach(pos)) {
                entries.remove();
                continue;
            }
            if (inside(body, pos, state)
                || path != null && crosses(state.cycle(BlockStateProperties.OPEN), pos, path)) {
                continue;
            }
            BlockPos target = clickFor(pos, state, true);
            entries.remove();
            // An iron door a button opened shuts when the button comes back out.
            if (target != null) {
                click(target, pos);
                return;
            }
        }
    }

    // The line the middle of your body walks along. The leaf of an open door or trapdoor lies
    // along one side of its block and in a doorway never reaches it. Opening is still worth it
    // when the leaf would only catch the edge of your body.
    private AABB middleOf(AABB path, Vec3 heading) {
        double half = mc.player.getBbWidth() / 2;
        return path.deflate(half * Math.abs(heading.z), 0, half * Math.abs(heading.x));
    }

    // The game shows a door or a button moved at once. A lever only moves once the server says
    // it did and reads as it was until then. The lever and its door are held for that long. A
    // second click would put the lever back and the door would look as if it never opened.
    private void click(BlockPos pos, BlockPos key) {
        boolean lever = BlockUtil.state(pos).getBlock() instanceof LeverBlock;
        int hold = lever ? Math.max(CLICK_COOLDOWN, ServerInfo.answerTicks()) : CLICK_COOLDOWN;
        clicked.put(key, hold);
        if (lever) {
            clicked.put(pos, hold);
        }
        BlockUtil.interact(pos, face.getValue());
    }

    private boolean handles(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock door) {
            return door.type().canOpenByHand() ? doors.isOn() : ironDoors.isOn();
        }
        if (block instanceof FenceGateBlock) {
            return gates.isOn();
        }
        if (block instanceof TrapDoorBlock trapdoor) {
            return trapdoor.getType().canOpenByHand() ? trapdoors.isOn() : ironDoors.isOn();
        }
        return false;
    }

    private static boolean opensByHand(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock door) {
            return door.type().canOpenByHand();
        }
        return !(block instanceof TrapDoorBlock trapdoor) || trapdoor.getType().canOpenByHand();
    }

    // Only a lever shuts an iron door again. Null when there is nothing to click.
    private BlockPos clickFor(BlockPos key, BlockState state, boolean shutting) {
        if (opensByHand(state)) {
            return key;
        }
        BlockPos top = state.getBlock() instanceof DoorBlock ? key.above() : key;
        Iterable<BlockPos> around = BlockPos.betweenClosed(key.offset(-SWITCH_REACH, -SWITCH_REACH, -SWITCH_REACH),
            top.offset(SWITCH_REACH, SWITCH_REACH, SWITCH_REACH));
        return BlockUtil.nearestOf(around, pos -> {
            BlockState found = BlockUtil.state(pos);
            return !clicked.contains(pos) && flips(found, shutting) && BlockUtil.serverReaches(pos)
                && (Switches.powers(mc.level, pos, found, key) || Switches.powers(mc.level, pos, found, top));
        });
    }

    // A lever that is off opens and one that is on shuts. A button only goes in and one already
    // in does nothing.
    private static boolean flips(BlockState state, boolean shutting) {
        if (state.getBlock() instanceof LeverBlock) {
            return state.getValue(LeverBlock.POWERED) == shutting;
        }
        return !shutting && state.getBlock() instanceof ButtonBlock && !state.getValue(ButtonBlock.POWERED);
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
