package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.movement.VehicleFly;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
public abstract class MobMixin {

    // The client only steers a saddled mount. VehicleFly can pretend they all are.
    @Inject(method = "isSaddled()Z", at = @At("HEAD"), cancellable = true)
    private void onIsSaddled(CallbackInfoReturnable<Boolean> cir) {
        VehicleFly vehicleFly = Modules.get(VehicleFly.class);
        if (vehicleFly != null && vehicleFly.spoofsSaddle()) {
            cir.setReturnValue(true);
        }
    }
}
