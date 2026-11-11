package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.LoadedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

// Gives a full cube to blocks that normally let you walk straight through them.
public final class Collisions extends Module {

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "Blocks that become solid to you. Click to pick them. "
            + "Water and webs and berry bushes still catch a fall that would hurt.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.COBWEB, Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW));
    private final BoolSetting magma = new BoolSetting("Magma",
        "Makes the air over magma solid to keep you off it.", false);
    private final BoolSetting unloadedChunks = new BoolSetting("Unloaded chunks",
        "Stops you walking or riding into a chunk that has not loaded.", false);
    private final BoolSetting ignoreBorder = new BoolSetting("Ignore border",
        "Walks through the world border instead of stopping at it.", false);

    public Collisions() {
        super("Collisions", "Turns fire and webs and other soft blocks into walls.", Category.WORLD);
        addSettings(blocks, magma, unloadedChunks, ignoreBorder);
        searchTags("collision", "hitbox", "world border", "magma");
    }

    // Null leaves the block with the shape the game gave it. The ridden vehicle asks as well.
    public VoxelShape forcedShape(BlockState state, BlockPos pos) {
        if (!inGame()) {
            return null;
        }
        if (blocks.contains(state.getBlock()) && !swimmingIn(state) && !catchesFall(state)) {
            return Shapes.block();
        }
        // Sneaking lets you drop onto the magma on purpose.
        if (magma.isOn() && state.isAir() && !mc.player.isShiftKeyDown()
            && mc.level.getBlockState(pos.below()).is(Blocks.MAGMA_BLOCK)) {
            return Shapes.block();
        }
        return null;
    }

    // A picked water or lava stays soft whilst you are in it. You could never swim out otherwise.
    private boolean swimmingIn(BlockState state) {
        if (!(state.getBlock() instanceof LiquidBlock)) {
            return false;
        }
        Entity mover = mc.player.getRootVehicle();
        return state.getFluidState().is(FluidTags.LAVA) ? mover.isInLava() : mover.isInWater();
    }

    // A picked water or web or berry bush or climbable goes soft once landing on top would hurt.
    // The fall then ends inside it as if the module were off. This tick's drop counts too.
    private boolean catchesFall(BlockState state) {
        Entity mover = mc.player.getRootVehicle();
        double fall = mover.fallDistance - Math.min(0, mover.getDeltaMovement().y);
        return fall > DamageUtil.safeFall(mover)
            && (state.getFluidState().is(FluidTags.WATER) || state.is(BlockTags.FALL_DAMAGE_RESETTING));
    }

    public boolean ignoresBorder() {
        return ignoreBorder.isOn();
    }

    // Walking out of the loaded world puts you back where this tick began. A teleport sets that
    // spot too and you wait there for the chunk.
    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!unloadedChunks.isOn() || !inGame() || LoadedChunks.has(mc.player.getX(), mc.player.getZ())
            || !LoadedChunks.has(mc.player.xo, mc.player.zo)) {
            return;
        }
        mc.player.setPos(mc.player.xo, mc.player.getY(), mc.player.zo);
        Vec3 motion = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(0, motion.y, 0);
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!unloadedChunks.isOn() || !inGame()) {
            return;
        }
        if (event.getPacket() instanceof ServerboundMovePlayerPacket move) {
            if (!LoadedChunks.has(move.getX(mc.player.getX()), move.getZ(mc.player.getZ()))) {
                event.cancel();
            }
            return;
        }
        if (event.getPacket() instanceof ServerboundMoveVehiclePacket move) {
            Vec3 where = move.movingTo().position();
            if (LoadedChunks.has(where.x, where.z)) {
                return;
            }
            Entity vehicle = mc.player.getVehicle();
            if (vehicle != null) {
                vehicle.absSnapTo(vehicle.xo, vehicle.yo, vehicle.zo);
            }
            event.cancel();
        }
    }
}
