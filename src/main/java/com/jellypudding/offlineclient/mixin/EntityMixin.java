package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.combat.Hitboxes;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {

    // The pick radius inflates the box the crosshair tests against.
    @Inject(method = "getPickRadius", at = @At("HEAD"), cancellable = true)
    private void onGetPickRadius(CallbackInfoReturnable<Float> cir) {
        Hitboxes hitboxes = Modules.get(Hitboxes.class);
        if (hitboxes == null) {
            return;
        }
        double extra = hitboxes.expansionFor((Entity) (Object) this);
        if (extra > 0) {
            cir.setReturnValue((float) extra);
        }
    }
}
