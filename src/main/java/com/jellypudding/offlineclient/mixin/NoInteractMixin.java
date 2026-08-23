package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.player.AutoEat;
import com.jellypudding.offlineclient.modules.player.AutoGap;
import com.jellypudding.offlineclient.modules.player.NoInteract;
import com.jellypudding.offlineclient.util.Modules;
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

    // Held between the two startUseItem hooks on the render thread only.
    @Unique
    private HitResult offlineclient$realHit;

    @Inject(method = "startAttack()Z", at = @At("HEAD"), cancellable = true)
    private void onStartAttack(CallbackInfoReturnable<Boolean> cir) {
        NoInteract noInteract = Modules.get(NoInteract.class);
        if (noInteract == null || !noInteract.isEnabled()) {
            return;
        }
        if (OfflineClient.MC.hitResult != null && noInteract.blocksAttack(OfflineClient.MC.hitResult)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Blanks the crosshair target whilst a feeder holds the use key. Without
     * this a chest or a villager under the crosshair swallows the bite.
     */
    @Inject(method = "startUseItem()V", at = @At("HEAD"))
    private void onStartUseItem(CallbackInfo ci) {
        Minecraft mc = OfflineClient.MC;
        if (mc.hitResult == null || mc.hitResult.getType() == HitResult.Type.MISS
            || !offlineclient$feeding()) {
            return;
        }
        offlineclient$realHit = mc.hitResult;
        mc.hitResult = BlockHitResult.miss(mc.hitResult.getLocation(), Direction.UP,
            BlockPos.containing(mc.hitResult.getLocation()));
    }

    @Inject(method = "startUseItem()V", at = @At("RETURN"))
    private void afterStartUseItem(CallbackInfo ci) {
        if (offlineclient$realHit != null) {
            OfflineClient.MC.hitResult = offlineclient$realHit;
            offlineclient$realHit = null;
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
        NoInteract noInteract = Modules.get(NoInteract.class);
        if (noInteract == null || !noInteract.isEnabled()) {
            return;
        }
        if (OfflineClient.MC.hitResult instanceof BlockHitResult hit
            && noInteract.blocksMining(hit.getBlockPos())) {
            ci.cancel();
        }
    }
}
