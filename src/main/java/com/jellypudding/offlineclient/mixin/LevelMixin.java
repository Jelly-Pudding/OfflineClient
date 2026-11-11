package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Weather;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public class LevelMixin {

    @Inject(method = "getRainLevel(F)F", at = @At("HEAD"), cancellable = true)
    private void onGetRainLevel(CallbackInfoReturnable<Float> cir) {
        Weather weather = offlineclient$weather();
        if (weather != null) {
            cir.setReturnValue(weather.rainLevel());
        }
    }

    @Inject(method = "getThunderLevel(F)F", at = @At("HEAD"), cancellable = true)
    private void onGetThunderLevel(CallbackInfoReturnable<Float> cir) {
        Weather weather = offlineclient$weather();
        if (weather != null) {
            cir.setReturnValue(weather.thunderLevel());
        }
    }

    // The integrated server's levels keep the real weather that drives storms and snow.
    @Unique
    private Weather offlineclient$weather() {
        if (!((Object) this instanceof ClientLevel) || Weather.paused()) {
            return null;
        }
        return Modules.active(Weather.class);
    }
}
