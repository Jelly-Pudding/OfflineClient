package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.player.GhostHand;
import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.modules.render.XRay;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {

    // No ambient occlusion shading whilst XRay is on.
    @Inject(
        method = "getShadeBrightness(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F",
        at = @At("RETURN"),
        cancellable = true)
    private void onGetShadeBrightness(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        XRay xray = XRay.get();
        if (xray != null && xray.isEnabled()) {
            cir.setReturnValue(1f);
        }
    }

    // GhostHand empties the outline of every block it cannot open so the
    // crosshair reaches the container behind the wall.
    @Inject(
        method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("HEAD"), cancellable = true)
    private void onGetShape(BlockGetter level, BlockPos pos, CollisionContext context,
                            CallbackInfoReturnable<VoxelShape> cir) {
        if (context == CollisionContext.empty()) {
            return;
        }
        GhostHand ghostHand = Modules.get(GhostHand.class);
        if (ghostHand != null && ghostHand.passesThrough(pos)) {
            cir.setReturnValue(Shapes.empty());
        }
    }

    // The offset and the seed are what make grass and flowers sit differently in each spot.
    @Inject(method = "getOffset(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("HEAD"), cancellable = true)
    private void onGetOffset(BlockPos pos, CallbackInfoReturnable<Vec3> cir) {
        if (offlineclient$fixedRotations()) {
            cir.setReturnValue(Vec3.ZERO);
        }
    }

    @Inject(method = "getSeed(Lnet/minecraft/core/BlockPos;)J", at = @At("HEAD"), cancellable = true)
    private void onGetSeed(BlockPos pos, CallbackInfoReturnable<Long> cir) {
        if (offlineclient$fixedRotations()) {
            cir.setReturnValue(0L);
        }
    }

    @Unique
    private static boolean offlineclient$fixedRotations() {
        NoRender noRender = Modules.get(NoRender.class);
        return noRender != null && noRender.fixesTextureRotations();
    }
}
