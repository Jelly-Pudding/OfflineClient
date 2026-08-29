package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.player.LiquidInteract;
import com.jellypudding.offlineclient.modules.player.NoMiningTrace;
import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public abstract class PickMixin {

    // Freecam swaps the whole ray for one cast from the camera.
    @Inject(method = "raycastHitResult(FLnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/HitResult;",
        at = @At("HEAD"),
        cancellable = true)
    private void onPickFromCamera(float partialTicks, Entity camera,
                                  CallbackInfoReturnable<HitResult> cir) {
        Freecam freecam = Modules.active(Freecam.class);
        if (freecam != null && freecam.interactsFromCamera()) {
            cir.setReturnValue(freecam.pick((LocalPlayer) (Object) this, partialTicks));
        }
    }

    // Runs once per frame after vanilla has picked.
    @Inject(method = "raycastHitResult(FLnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/HitResult;",
        at = @At("RETURN"),
        cancellable = true)
    private void onRaycastHitResult(float partialTicks, Entity camera,
                                    CallbackInfoReturnable<HitResult> cir) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        HitResult result = cir.getReturnValue();
        if (result == null || camera == null) {
            return;
        }

        NoMiningTrace noTrace = Modules.get(NoMiningTrace.class);
        if (noTrace != null && result instanceof EntityHitResult hit && noTrace.ignores(hit.getEntity())) {
            result = offlineclient$clip(player, camera, partialTicks, ClipContext.Fluid.NONE);
            cir.setReturnValue(result);
        }

        LiquidInteract liquid = Modules.active(LiquidInteract.class);
        if (liquid == null) {
            return;
        }
        BlockHitResult fluidHit = offlineclient$clip(player, camera, partialTicks,
            liquid.includesFlowing() ? ClipContext.Fluid.ANY : ClipContext.Fluid.SOURCE_ONLY);
        if (fluidHit.getType() == HitResult.Type.MISS) {
            return;
        }
        Vec3 eye = camera.getEyePosition(partialTicks);
        if (result.getType() == HitResult.Type.MISS
            || fluidHit.getLocation().distanceToSqr(eye) < result.getLocation().distanceToSqr(eye)) {
            cir.setReturnValue(fluidHit);
        }
    }

    // The same block ray vanilla casts with a chosen liquid rule.
    @Unique
    private static BlockHitResult offlineclient$clip(LocalPlayer player, Entity camera,
                                                     float partialTicks, ClipContext.Fluid fluid) {
        double range = player.blockInteractionRange();
        Vec3 eye = camera.getEyePosition(partialTicks);
        Vec3 end = eye.add(camera.getViewVector(partialTicks).scale(range));
        return player.level().clip(
            new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, player));
    }
}
