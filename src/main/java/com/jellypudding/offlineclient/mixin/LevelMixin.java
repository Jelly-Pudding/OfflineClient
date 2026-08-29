package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Weather;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public class LevelMixin {

    @Inject(method = "getRainLevel(F)F", at = @At("HEAD"), cancellable = true)
    private void onGetRainLevel(float delta, CallbackInfoReturnable<Float> cir) {
        Weather weather = Modules.active(Weather.class);
        if (weather != null) {
            cir.setReturnValue(weather.rainLevel());
        }
    }

    @Inject(method = "getThunderLevel(F)F", at = @At("HEAD"), cancellable = true)
    private void onGetThunderLevel(float delta, CallbackInfoReturnable<Float> cir) {
        Weather weather = Modules.active(Weather.class);
        if (weather != null) {
            cir.setReturnValue(weather.thunderLevel());
        }
    }
}
