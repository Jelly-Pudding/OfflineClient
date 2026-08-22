package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.modules.render.XRay;
import com.jellypudding.offlineclient.modules.render.Zoom;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V",
            shift = At.Shift.AFTER))
    private void onUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (OfflineClient.INSTANCE.getModuleManager() == null) {
            return;
        }
        Freecam freecam = OfflineClient.INSTANCE.getModuleManager().get(Freecam.class);
        if (!freecam.isEnabled()) {
            return;
        }
        detached = true;
        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(true);
        setPosition(freecam.getCamPos(partialTicks));
        setRotation(freecam.getCamYaw(), freecam.getCamPitch());
    }

    /**
     * XRay turns off the smart chunk culling. Vanilla skips chunk
     * sections that are boxed in by solid ground.
     */
    @Inject(method = "extractRenderState(Lnet/minecraft/client/renderer/state/level/CameraRenderState;F)V",
        at = @At("RETURN"))
    private void onExtractRenderState(CameraRenderState state, float partialTicks, CallbackInfo ci) {
        XRay xray = XRay.get();
        if (xray != null && xray.isEnabled()) {
            state.smartCull = false;
        }
    }

    /** Zoom divides the FOV at read time. */
    @ModifyReturnValue(method = "calculateFov(F)F", at = @At("RETURN"))
    private float onCalculateFov(float original) {
        if (OfflineClient.INSTANCE.getModuleManager() == null) {
            return original;
        }
        return OfflineClient.INSTANCE.getModuleManager().get(Zoom.class).applyZoom(original);
    }

    /** Hides the hand while zoomed in. */
    @ModifyReturnValue(method = "calculateHudFov(F)F", at = @At("RETURN"))
    private float onCalculateHudFov(float original) {
        if (OfflineClient.INSTANCE.getModuleManager() == null) {
            return original;
        }
        return OfflineClient.INSTANCE.getModuleManager().get(Zoom.class).applyZoom(original);
    }
}
