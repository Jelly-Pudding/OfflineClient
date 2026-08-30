package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

// Opens containers through walls.
public final class GhostHand extends Module {

    public GhostHand() {
        super("GhostHand", "Opens containers through walls.", Category.PLAYER);
        searchTags("through walls", "chest");
    }

    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (event.isCancelled() || !inGame() || mc.gameMode == null) {
            return;
        }
        if (mc.player.isSpectator() || mc.player.isShiftKeyDown()) {
            return;
        }
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && mc.level.getBlockState(hit.getBlockPos())
                .getMenuProvider(mc.level, hit.getBlockPos()) != null) {
            return;
        }

        double reach = mc.player.blockInteractionRange();
        Vec3 eye = mc.player.getEyePosition();
        Vec3 step = mc.player.getViewVector(1f).scale(0.1);
        Set<BlockPos> visited = new HashSet<>();

        for (int i = 1; i <= reach * 10; i++) {
            BlockPos pos = BlockPos.containing(eye.add(step.scale(i)));
            if (!visited.add(pos)) {
                continue;
            }
            if (mc.level.getBlockState(pos).getMenuProvider(mc.level, pos) == null) {
                continue;
            }
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, true);
            InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            if (result.consumesAction()) {
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
            event.cancel();
            return;
        }
    }
}
