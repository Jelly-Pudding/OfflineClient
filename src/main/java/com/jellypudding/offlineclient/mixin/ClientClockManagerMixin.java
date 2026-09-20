package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.TimeChanger;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// The instance carries no sign of which world clock it belongs to. The
// overworld one is noted here and answered in ClientClockInstanceMixin.
@Mixin(ClientClockManager.class)
public abstract class ClientClockManagerMixin {

    @Inject(method = "getInstance(Lnet/minecraft/core/Holder;)"
        + "Lnet/minecraft/client/ClientClockManager$ClientClockInstance;",
        at = @At("RETURN"))
    private void onGetInstance(Holder<WorldClock> clock,
                               CallbackInfoReturnable<ClientClockManager.ClientClockInstance> cir) {
        if (clock.is(WorldClocks.OVERWORLD)) {
            TimeChanger.noteOverworldClock(cir.getReturnValue());
        }
    }
}
