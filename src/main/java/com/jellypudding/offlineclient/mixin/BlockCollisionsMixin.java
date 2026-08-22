package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.Jesus;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every block shape the player can bump into passes through here. Jesus
 * swaps liquid shapes for a full block.
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsMixin {

    @WrapOperation(method = "computeNext()Ljava/lang/Object;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/shapes/CollisionContext;getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape wrapCollisionShape(CollisionContext context, BlockState state,
                                          CollisionGetter getter, BlockPos pos,
                                          Operation<VoxelShape> original) {
        VoxelShape shape = original.call(context, state, getter, pos);
        if (OfflineClient.INSTANCE.getModuleManager() == null) {
            return shape;
        }
        if (!(context instanceof EntityCollisionContext entityContext)
            || entityContext.getEntity() == null
            || entityContext.getEntity() != OfflineClient.MC.player) {
            return shape;
        }
        return OfflineClient.INSTANCE.getModuleManager().get(Jesus.class).adjustShape(state, pos, shape);
    }
}
