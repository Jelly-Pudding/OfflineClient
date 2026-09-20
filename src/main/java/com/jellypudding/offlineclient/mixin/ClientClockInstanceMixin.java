package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.TimeChanger;
import com.jellypudding.offlineclient.util.Modules;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// The sky and the moon and every clock item read the day through here.
@Mixin(ClientClockManager.ClientClockInstance.class)
public abstract class ClientClockInstanceMixin {

    @ModifyReturnValue(method = "totalTicks()J", at = @At("RETURN"))
    private long onTotalTicks(long original) {
        TimeChanger timeChanger = Modules.active(TimeChanger.class);
        if (timeChanger != null && TimeChanger.isOverworldClock(this)) {
            return timeChanger.clockTime();
        }
        return original;
    }
}
