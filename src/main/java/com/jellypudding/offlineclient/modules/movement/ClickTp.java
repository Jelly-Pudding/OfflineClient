package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

// Teleports you onto the block you right click. The server only grants so much
// movement per packet each tick so a long trip is walked as one hop a tick.
public final class ClickTp extends Module {

    // Three fillers then the hop itself. The fifth is left for the client's own packet.
    private static final int FILLER_PACKETS = 3;

    // The fourth packet allows four hundred squared blocks which is twenty of travel.
    private static final double MAX_HOP = 19.9;

    private final NumberSetting range = new NumberSetting("Range",
        "How far away the clicked block may be.", 100, 10, 200, 10, " blocks");

    // Where the current trip ends. Null whilst there is no trip.
    private Vec3 destination;

    public ClickTp() {
        super("ClickTp", "Teleports you to the block you right click.", Category.MOVEMENT);
        addSettings(range);
        searchTags("teleport", "click teleport");
    }

    @Override
    protected void onEnable() {
        destination = null;
    }

    @Override
    protected void onDisable() {
        destination = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isDeadOrDying()) {
            destination = null;
            return;
        }
        if (destination != null) {
            hop();
            return;
        }
        if (!mc.options.keyUse.isDown() || mc.gui.screen() != null) {
            return;
        }
        if (mc.player.getMainHandItem().getUseAnimation() != ItemUseAnimation.NONE) {
            return;
        }
        if (busyWithTarget()) {
            return;
        }
        BlockHitResult hit = lookRay();
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.useWithoutItem(mc.level, mc.player, hit) != InteractionResult.PASS) {
            return;
        }
        VoxelShape shape = state.getCollisionShape(mc.level, pos);
        if (shape.isEmpty()) {
            shape = state.getShape(mc.level, pos);
        }
        double top = shape.isEmpty() ? 1 : shape.max(Direction.Axis.Y);
        Direction side = hit.getDirection();
        destination = new Vec3(pos.getX() + 0.5 + side.getStepX(), pos.getY() + top,
            pos.getZ() + 0.5 + side.getStepZ());
        hop();
    }

    // A use on an entity or a block placement keeps its normal meaning.
    private boolean busyWithTarget() {
        HitResult hit = mc.hitResult;
        if (hit == null) {
            return false;
        }
        if (hit.getType() == HitResult.Type.ENTITY) {
            return true;
        }
        return hit.getType() == HitResult.Type.BLOCK
            && mc.player.getMainHandItem().getItem() instanceof BlockItem;
    }

    private BlockHitResult lookRay() {
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 from = camera.position();
        Vec3 to = from.add(Vec3.directionFromRotation(camera.xRot(), camera.yRot())
            .scale(range.getValue()));
        return mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE, mc.player));
    }

    // Moves as far along the trip as one tick of packets is allowed to carry.
    private void hop() {
        Vec3 from = mc.player.position();
        double left = from.distanceTo(destination);
        boolean arriving = left <= MAX_HOP;
        Vec3 step = arriving
            ? destination
            : from.add(destination.subtract(from).scale(MAX_HOP / left));

        for (int i = 0; i < FILLER_PACKETS; i++) {
            mc.player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, true));
        }
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(step, true, true));
        mc.player.setPos(step);
        // The rest of the tick still runs the physics. Gravity between hops would
        // otherwise gather into a fall the landing has to pay for.
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.fallDistance = 0;

        if (arriving) {
            destination = null;
        }
    }
}
