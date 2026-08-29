package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.movement.NoSlowdown;
import com.jellypudding.offlineclient.modules.movement.Sprint;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FoodData.class)
public abstract class FoodDataMixin {

    // The only thing the client asks this for is whether sprinting is allowed.
    @Inject(method = "hasEnoughFood()Z", at = @At("HEAD"), cancellable = true)
    private void onHasEnoughFood(CallbackInfoReturnable<Boolean> cir) {
        NoSlowdown noSlowdown = Modules.get(NoSlowdown.class);
        Sprint sprint = Modules.get(Sprint.class);
        if ((noSlowdown != null && noSlowdown.skipsHunger())
            || (sprint != null && sprint.sprintsHungry())) {
            cir.setReturnValue(true);
        }
    }
}
