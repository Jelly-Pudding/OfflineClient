package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.CameraTweaks;
import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.modules.render.FreeLook;
import com.jellypudding.offlineclient.modules.render.WallHack;
import com.jellypudding.offlineclient.modules.render.XRay;
import com.jellypudding.offlineclient.modules.render.Zoom;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    // The world and the hand each read the field of view. One ease a frame keeps them level.
    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void onCameraUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        Zoom zoom = Modules.get(Zoom.class);
        if (zoom != null) {
            zoom.advance();
        }
    }

    // FreeLook lends its look angles to the camera entity for the alignment call.
    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V"))
    private void beforeAlignWithEntity(DeltaTracker deltaTracker, CallbackInfo ci) {
        FreeLook freeLook = Modules.get(FreeLook.class);
        if (freeLook != null) {
            freeLook.applyRotation();
        }
    }

    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V",
            shift = At.Shift.AFTER))
    private void afterAlignWithEntity(DeltaTracker deltaTracker, CallbackInfo ci) {
        FreeLook freeLook = Modules.get(FreeLook.class);
        if (freeLook != null) {
            freeLook.restoreRotation();
        }
        Freecam freecam = Modules.active(Freecam.class);
        if (freecam == null) {
            return;
        }
        detached = true;
        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(true);
        setPosition(freecam.getCamPos(partialTicks));
        setRotation(freecam.getCamYaw(), freecam.getCamPitch());
    }

    // Vanilla skips chunk sections that are boxed in by solid ground.
    @Inject(method = "extractRenderState(Lnet/minecraft/client/renderer/state/level/CameraRenderState;F)V",
        at = @At("RETURN"))
    private void onExtractRenderState(CameraRenderState state, float partialTicks, CallbackInfo ci) {
        XRay xray = XRay.get();
        WallHack wallHack = Modules.get(WallHack.class);
        if ((xray != null && xray.isEnabled()) || (wallHack != null && wallHack.showsHiddenChunks())) {
            state.smartCull = false;
        }
    }

    @ModifyReturnValue(method = "calculateFov(F)F", at = @At("RETURN"))
    private float onCalculateFov(float original) {
        Zoom zoom = Modules.get(Zoom.class);
        return zoom == null ? original : zoom.applyZoom(original);
    }

    @ModifyReturnValue(method = "calculateHudFov(F)F", at = @At("RETURN"))
    private float onCalculateHudFov(float original) {
        Zoom zoom = Modules.get(Zoom.class);
        return zoom == null ? original : zoom.applyZoom(original);
    }

    // The distance vanilla wants before it walks the ray out and shortens it.
    @ModifyVariable(method = "getMaxZoom(F)F", at = @At("HEAD"), argsOnly = true)
    private float onWantedZoom(float wanted) {
        CameraTweaks tweaks = Modules.get(CameraTweaks.class);
        return tweaks == null ? wanted : tweaks.adjustDistance(wanted);
    }

    // No clip returns the wanted distance whole and skips the ray walk.
    @Inject(method = "getMaxZoom(F)F", at = @At("HEAD"), cancellable = true)
    private void onGetMaxZoom(float wanted, CallbackInfoReturnable<Float> cir) {
        CameraTweaks tweaks = Modules.get(CameraTweaks.class);
        if (tweaks != null && tweaks.passesThroughWalls()) {
            cir.setReturnValue(tweaks.adjustDistance(wanted));
        }
    }
}
