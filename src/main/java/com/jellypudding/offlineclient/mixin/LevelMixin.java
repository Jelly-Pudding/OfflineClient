package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.render.ClearSkies;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public class LevelMixin {

    @Inject(method = "getRainLevel(F)F", at = @At("HEAD"), cancellable = true)
    private void onGetRainLevel(float delta, CallbackInfoReturnable<Float> cir) {
        if (offlineclient$clearSkies()) {
            cir.setReturnValue(0f);
        }
    }

    @Inject(method = "getThunderLevel(F)F", at = @At("HEAD"), cancellable = true)
    private void onGetThunderLevel(float delta, CallbackInfoReturnable<Float> cir) {
        if (offlineclient$clearSkies()) {
            cir.setReturnValue(0f);
        }
    }

    private static boolean offlineclient$clearSkies() {
        if (OfflineClient.INSTANCE.getModuleManager() == null) {
            return false;
        }
        return OfflineClient.INSTANCE.getModuleManager().get(ClearSkies.class).isEnabled();
    }
}
