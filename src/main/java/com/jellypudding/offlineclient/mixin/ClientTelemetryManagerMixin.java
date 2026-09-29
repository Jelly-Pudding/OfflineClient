package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.Privacy;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import net.minecraft.client.telemetry.TelemetryEventSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// The sender for reports outside a world and the one opened for every world you join both come
// from here.
@Mixin(ClientTelemetryManager.class)
public abstract class ClientTelemetryManagerMixin {

    @ModifyReturnValue(method = "createEventSender()Lnet/minecraft/client/telemetry/TelemetryEventSender;",
        at = @At("RETURN"))
    private TelemetryEventSender onCreateEventSender(TelemetryEventSender original) {
        return Privacy.gate(original);
    }
}
