package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.Jesus;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.PowderSnowBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PowderSnowBlock.class)
public abstract class PowderSnowBlockMixin {

    // Vanilla asks this for leather boots. A yes gives the snow a solid top.
    @Inject(method = "canEntityWalkOnPowderSnow(Lnet/minecraft/world/entity/Entity;)Z",
        at = @At("HEAD"), cancellable = true)
    private static void onCanWalk(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        Jesus jesus = Modules.get(Jesus.class);
        if (entity == OfflineClient.MC.player && jesus != null && jesus.walksOnPowderSnow()) {
            cir.setReturnValue(true);
        }
    }
}
