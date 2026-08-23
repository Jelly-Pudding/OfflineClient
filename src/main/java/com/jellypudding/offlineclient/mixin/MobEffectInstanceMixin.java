package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.player.PotionSaver;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MobEffectInstance.class)
public abstract class MobEffectInstanceMixin {

    // The client counts every effect down here once a tick.
    @Inject(method = "tickDownDuration()V", at = @At("HEAD"), cancellable = true)
    private void onTickDownDuration(CallbackInfo ci) {
        PotionSaver potionSaver = Modules.get(PotionSaver.class);
        if (potionSaver != null && potionSaver.shouldFreeze((MobEffectInstance) (Object) this)) {
            ci.cancel();
        }
    }
}
