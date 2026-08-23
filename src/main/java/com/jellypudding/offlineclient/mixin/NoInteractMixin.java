package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.player.AutoEat;
import com.jellypudding.offlineclient.modules.player.AutoGap;
import com.jellypudding.offlineclient.modules.player.NoInteract;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class NoInteractMixin {

    @Inject(method = "startAttack()Z", at = @At("HEAD"), cancellable = true)
    private void onStartAttack(CallbackInfoReturnable<Boolean> cir) {
        NoInteract noInteract = Modules.active(NoInteract.class);
        if (noInteract == null) {
            return;
        }
        if (OfflineClient.MC.hitResult != null && noInteract.blocksAttack(OfflineClient.MC.hitResult)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Blanks the crosshair target whilst a feeder holds the use key. Without
     * this a chest or a villager under the crosshair swallows the bite. The
     * real target goes back even where another mixin cancels the call.
     */
    @WrapMethod(method = "startUseItem()V")
    private void wrapStartUseItem(Operation<Void> original) {
        Minecraft mc = OfflineClient.MC;
        HitResult real = mc.hitResult;
        if (real == null || real.getType() == HitResult.Type.MISS || !offlineclient$feeding()) {
            original.call();
            return;
        }
        mc.hitResult = BlockHitResult.miss(real.getLocation(), Direction.UP,
            BlockPos.containing(real.getLocation()));
        try {
            original.call();
        } finally {
            mc.hitResult = real;
        }
    }

    @Unique
    private static boolean offlineclient$feeding() {
        AutoEat autoEat = Modules.get(AutoEat.class);
        if (autoEat != null && autoEat.isBusy()) {
            return true;
        }
        AutoGap autoGap = Modules.get(AutoGap.class);
        return autoGap != null && autoGap.isBusy();
    }

    @Inject(method = "continueAttack(Z)V", at = @At("HEAD"), cancellable = true)
    private void onContinueAttack(boolean holding, CallbackInfo ci) {
        NoInteract noInteract = Modules.active(NoInteract.class);
        if (noInteract == null) {
            return;
        }
        if (OfflineClient.MC.hitResult instanceof BlockHitResult hit
            && noInteract.blocksMining(hit.getBlockPos())) {
            ci.cancel();
        }
    }
}
