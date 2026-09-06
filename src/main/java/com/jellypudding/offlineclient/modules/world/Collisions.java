package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

// Gives a full cube to blocks that normally let you walk straight through them.
public final class Collisions extends Module {

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "Blocks that become solid to you. Click to pick them.", BuiltInRegistries.BLOCK, List.of());
    private final BoolSetting magma = new BoolSetting("Magma",
        "Makes the air over a magma block solid so you cannot step on it.", false);
    private final BoolSetting unloadedChunks = new BoolSetting("Unloaded chunks",
        "Stops you walking or riding into a chunk that has not loaded.", false);
    private final BoolSetting ignoreBorder = new BoolSetting("Ignore border",
        "Walks through the world border instead of stopping at it.", false);

    // The last spot inside a loaded chunk. Nought whilst nothing is known.
    private double safeX;
    private double safeZ;
    private boolean haveSafe;

    public Collisions() {
        super("Collisions", "Turns fire and webs and other soft blocks into walls.", Category.WORLD);
        addSettings(blocks, magma, unloadedChunks, ignoreBorder);
        searchTags("collision", "hitbox", "world border", "magma");
    }

    @Override
    protected void onEnable() {
        haveSafe = false;
    }

    // Null leaves the block with the shape the game gave it.
    public VoxelShape forcedShape(BlockState state, BlockPos pos) {
        if (!state.getFluidState().isEmpty() || mc.player == null || mc.level == null) {
            return null;
        }
        if (blocks.contains(state.getBlock())) {
            return Shapes.block();
        }
        // Sneaking lets you drop onto the magma on purpose.
        if (magma.isOn() && state.isAir() && !mc.player.isShiftKeyDown()
            && mc.level.getBlockState(pos.below()).is(Blocks.MAGMA_BLOCK)) {
            return Shapes.block();
        }
        return null;
    }

    public boolean ignoresBorder() {
        return ignoreBorder.isOn();
    }

    // Walking out of the loaded world puts you back where you last stood.
    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!unloadedChunks.isOn() || !inGame()) {
            haveSafe = false;
            return;
        }
        if (loaded(mc.player.getX(), mc.player.getZ())) {
            safeX = mc.player.getX();
            safeZ = mc.player.getZ();
            haveSafe = true;
            return;
        }
        if (!haveSafe) {
            return;
        }
        mc.player.setPos(safeX, mc.player.getY(), safeZ);
        Vec3 motion = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(0, motion.y, 0);
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!unloadedChunks.isOn() || !inGame()) {
            return;
        }
        if (event.getPacket() instanceof ServerboundMovePlayerPacket move) {
            if (!loaded(move.getX(mc.player.getX()), move.getZ(mc.player.getZ()))) {
                event.cancel();
            }
            return;
        }
        if (event.getPacket() instanceof ServerboundMoveVehiclePacket move) {
            Vec3 where = move.position();
            if (loaded(where.x, where.z)) {
                return;
            }
            Entity vehicle = mc.player.getVehicle();
            if (vehicle != null) {
                vehicle.absSnapTo(vehicle.xo, vehicle.yo, vehicle.zo);
            }
            event.cancel();
        }
    }

    private boolean loaded(double x, double z) {
        return mc.level.getChunkSource().hasChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
    }
}
