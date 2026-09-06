package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Fullbright;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Fullbright raises the light every block is lit with before it is packed.
@Mixin(LightCoordsUtil.BrightnessGetter.class)
public interface BrightnessGetterMixin {

    @ModifyExpressionValue(method = "lambda$static$0", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/BlockAndLightGetter;getBrightness(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/BlockPos;)I",
        ordinal = 0))
    private static int onSkyLight(int sky) {
        Fullbright fullbright = Fullbright.get();
        return fullbright == null ? sky : Math.max(sky, fullbright.lightFloor(LightLayer.SKY));
    }

    @ModifyExpressionValue(method = "lambda$static$0", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/BlockAndLightGetter;getBrightness(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/BlockPos;)I",
        ordinal = 1))
    private static int onBlockLight(int block) {
        Fullbright fullbright = Fullbright.get();
        return fullbright == null ? block : Math.max(block, fullbright.lightFloor(LightLayer.BLOCK));
    }
}
