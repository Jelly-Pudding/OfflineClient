package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.render.FoliageTint;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.world.level.FoliageColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Spruce and birch leaves carry a fixed tint of their own that never asks the biome. Ambience
// would leave them green in a forest it paints any other colour.
@Mixin(BlockColors.class)
public abstract class BlockColorsMixin {

    @WrapOperation(method = "createDefault()Lnet/minecraft/client/color/block/BlockColors;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/color/block/BlockTintSources;constant(I)Lnet/minecraft/client/color/block/BlockTintSource;"))
    private static BlockTintSource paintLeaves(int colour, Operation<BlockTintSource> original) {
        BlockTintSource fixed = original.call(colour);
        return colour == FoliageColor.FOLIAGE_EVERGREEN || colour == FoliageColor.FOLIAGE_BIRCH
            ? new FoliageTint(fixed) : fixed;
    }
}
