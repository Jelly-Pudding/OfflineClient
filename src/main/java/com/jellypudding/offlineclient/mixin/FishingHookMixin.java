package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.NoKnockback;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FishingHook.class)
public abstract class FishingHookMixin {

    // A rod reeling the player in lands here on the client.
    @Inject(method = "pullEntity(Lnet/minecraft/world/entity/Entity;)V",
        at = @At("HEAD"), cancellable = true)
    private void onPullEntity(Entity entity, CallbackInfo ci) {
        NoKnockback noKnockback = Modules.get(NoKnockback.class);
        if (entity == OfflineClient.MC.player && noKnockback != null
            && noKnockback.blocksFishingRods()) {
            ci.cancel();
        }
    }
}
