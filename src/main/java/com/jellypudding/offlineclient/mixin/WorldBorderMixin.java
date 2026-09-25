package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.world.Collisions;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// The two calls that decide whether the border stops you.
@Mixin(WorldBorder.class)
public abstract class WorldBorderMixin {

    @Inject(method = "isInsideCloseToBorder(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z",
        at = @At("HEAD"), cancellable = true)
    private void onIsInsideCloseToBorder(CallbackInfoReturnable<Boolean> cir) {
        if (offlineclient$ignored()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isWithinBounds(Lnet/minecraft/core/BlockPos;)Z",
        at = @At("HEAD"), cancellable = true)
    private void onIsWithinBounds(CallbackInfoReturnable<Boolean> cir) {
        if (offlineclient$ignored()) {
            cir.setReturnValue(true);
        }
    }

    @Unique
    private static boolean offlineclient$ignored() {
        Collisions collisions = Modules.active(Collisions.class);
        return collisions != null && collisions.ignoresBorder();
    }
}
