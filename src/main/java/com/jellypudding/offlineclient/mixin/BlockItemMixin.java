package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.world.NoGhostBlocks;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    // Reporting success keeps the swing and the stack shrink and the placement packet.
    @Inject(
        method = "placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z",
        at = @At("HEAD"),
        cancellable = true)
    private void holdPlacement(BlockPlaceContext context, BlockState state,
                               CallbackInfoReturnable<Boolean> cir) {
        if (!context.getLevel().isClientSide()) {
            return;
        }
        NoGhostBlocks noGhostBlocks = Modules.get(NoGhostBlocks.class);
        if (noGhostBlocks != null && noGhostBlocks.holdsPlacements()) {
            cir.setReturnValue(true);
        }
    }
}
