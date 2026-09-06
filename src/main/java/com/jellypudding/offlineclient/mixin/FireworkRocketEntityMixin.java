package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.movement.ElytraBoost;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A client side rocket has no server copy to remove it. It goes when its
// flight is over and never bursts on anything it touches.
@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin {

    @Shadow
    private int life;

    @Shadow
    private int lifetime;

    @Inject(method = "tick()V", at = @At("TAIL"))
    private void discardSpentFake(CallbackInfo ci) {
        if (life > lifetime && offlineclient$isFake()) {
            ((FireworkRocketEntity) (Object) this).discard();
        }
    }

    @Inject(method = "onHitEntity(Lnet/minecraft/world/phys/EntityHitResult;)V",
        at = @At("HEAD"), cancellable = true)
    private void onHitEntity(EntityHitResult hit, CallbackInfo ci) {
        offlineclient$discardFake(ci);
    }

    @Inject(method = "onHitBlock(Lnet/minecraft/world/phys/BlockHitResult;)V",
        at = @At("HEAD"), cancellable = true)
    private void onHitBlock(BlockHitResult hit, CallbackInfo ci) {
        offlineclient$discardFake(ci);
    }

    @Unique
    private void offlineclient$discardFake(CallbackInfo ci) {
        if (offlineclient$isFake()) {
            ((FireworkRocketEntity) (Object) this).discard();
            ci.cancel();
        }
    }

    @Unique
    private boolean offlineclient$isFake() {
        ElytraBoost boost = Modules.get(ElytraBoost.class);
        return boost != null && boost.isFake((FireworkRocketEntity) (Object) this);
    }
}
