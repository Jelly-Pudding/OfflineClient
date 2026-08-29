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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Teleports you onto the block you right click. The move is split into
 * packets of ten blocks because the server refuses a longer single step.
 */
public final class ClickTp extends Module {

    private static final double PACKET_STEP = 10;

    // The server kicks for more steps than this in one tick.
    private static final int MAX_STEPS = 19;

    private final NumberSetting range = new NumberSetting("Range",
        "How far away the clicked block may be.", 100, 10, 200, 10, " blocks");

    public ClickTp() {
        super("ClickTp", "Teleports you to the block you right click.", Category.MOVEMENT);
        addSettings(range);
        searchTags("teleport", "click teleport");
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !mc.options.keyUse.isDown() || mc.gui.screen() != null) {
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
        Vec3 target = new Vec3(pos.getX() + 0.5 + side.getStepX(), pos.getY() + top,
            pos.getZ() + 0.5 + side.getStepZ());
        teleport(target);
    }

    // A normal use on an entity or a block placement keeps its normal meaning.
    private boolean busyWithTarget() {
        HitResult hit = mc.hitResult;
        if (hit == null) {
            return false;
        }
        if (hit.getType() == HitResult.Type.ENTITY) {
            EntityHitResult entityHit = (EntityHitResult) hit;
            return mc.player.interactOn(entityHit.getEntity(), InteractionHand.MAIN_HAND,
                hit.getLocation()) != InteractionResult.PASS;
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

    private void teleport(Vec3 target) {
        int steps = (int) Math.ceil(mc.player.position().distanceTo(target) / PACKET_STEP) - 1;
        if (steps > MAX_STEPS) {
            steps = 0;
        }
        for (int i = 0; i < steps; i++) {
            mc.player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, true));
        }
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(target, true, true));
        mc.player.setPos(target);
    }
}
