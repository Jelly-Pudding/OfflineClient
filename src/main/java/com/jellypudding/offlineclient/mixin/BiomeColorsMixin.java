package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Ambience;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// The tints a chunk build bakes into grass and leaves and water.
@Mixin(BiomeColors.class)
public abstract class BiomeColorsMixin {

    @Inject(method = "getAverageGrassColor", at = @At("HEAD"), cancellable = true)
    private static void onGrassColor(BlockAndTintGetter level, BlockPos pos,
                                     CallbackInfoReturnable<Integer> cir) {
        Ambience ambience = Modules.get(Ambience.class);
        if (ambience != null && ambience.paintsGrass()) {
            cir.setReturnValue(ambience.grassColor());
        }
    }

    @Inject(method = "getAverageFoliageColor", at = @At("HEAD"), cancellable = true)
    private static void onFoliageColor(BlockAndTintGetter level, BlockPos pos,
                                       CallbackInfoReturnable<Integer> cir) {
        Ambience ambience = Modules.get(Ambience.class);
        if (ambience != null && ambience.paintsFoliage()) {
            cir.setReturnValue(ambience.foliageColor());
        }
    }

    @Inject(method = "getAverageWaterColor", at = @At("HEAD"), cancellable = true)
    private static void onWaterColor(BlockAndTintGetter level, BlockPos pos,
                                     CallbackInfoReturnable<Integer> cir) {
        Ambience ambience = Modules.get(Ambience.class);
        if (ambience != null && ambience.paintsWater()) {
            cir.setReturnValue(ambience.waterColor());
        }
    }
}
