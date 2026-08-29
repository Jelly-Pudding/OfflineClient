package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.TimeChanger;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientClockManager.class)
public abstract class ClientClockManagerMixin {

    // The sky and the moon and every clock item read the day through here.
    @Inject(method = "getTotalTicks(Lnet/minecraft/core/Holder;)J", at = @At("HEAD"),
        cancellable = true)
    private void onGetTotalTicks(Holder<WorldClock> clock, CallbackInfoReturnable<Long> cir) {
        TimeChanger timeChanger = Modules.active(TimeChanger.class);
        if (timeChanger != null && clock.is(WorldClocks.OVERWORLD)) {
            cir.setReturnValue(timeChanger.clockTime());
        }
    }
}
