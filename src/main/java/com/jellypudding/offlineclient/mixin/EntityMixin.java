package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.combat.Hitboxes;
import com.jellypudding.offlineclient.modules.movement.AntiPush;
import com.jellypudding.offlineclient.modules.movement.Flight;
import com.jellypudding.offlineclient.modules.movement.NoSlowdown;
import com.jellypudding.offlineclient.modules.movement.NoFall;
import com.jellypudding.offlineclient.modules.movement.Step;
import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {

    @Shadow
    public boolean verticalCollisionBelow;

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

    @Inject(method = "isCurrentlyGlowing()Z", at = @At("HEAD"), cancellable = true)
    private void onIsCurrentlyGlowing(CallbackInfoReturnable<Boolean> cir) {
        NoRender noRender = Modules.get(NoRender.class);
        if (noRender != null && noRender.hidesGlowing()) {
            cir.setReturnValue(false);
        }
    }

    // Flight through fluids and NoSlowdown fluid drag. The local player is never in
    // water or lava as far as the physics and the swimming pose are concerned.
    @Inject(method = {"isInWater()Z", "isInLava()Z", "isUnderWater()Z"},
        at = @At("HEAD"), cancellable = true)
    private void onFluidCheck(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this != OfflineClient.MC.player) {
            return;
        }
        Flight flight = Modules.active(Flight.class);
        NoSlowdown noSlowdown = Modules.get(NoSlowdown.class);
        if ((flight != null && flight.throughFluids())
            || (noSlowdown != null && noSlowdown.skipsFluidDrag())) {
            cir.setReturnValue(false);
        }
    }

    // Slime and beds ask this before they bounce. NoFall answers yes to every landing.
    @Inject(method = "isSuppressingBounce()Z", at = @At("HEAD"), cancellable = true)
    private void onIsSuppressingBounce(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this != OfflineClient.MC.player) {
            return;
        }
        NoFall noFall = Modules.get(NoFall.class);
        if (noFall != null && noFall.suppressesBounce()) {
            cir.setReturnValue(true);
        }
    }

    // A crowd shove reaches the player only through this call. AntiPush keeps a share of it.
    @WrapOperation(method = "push(Lnet/minecraft/world/entity/Entity;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;push(DDD)V"))
    private void onPushedByEntity(Entity pushed, double x, double y, double z,
                                  Operation<Void> original) {
        AntiPush antiPush = pushed == OfflineClient.MC.player ? Modules.get(AntiPush.class) : null;
        double scale = antiPush == null ? 1 : antiPush.entityPushScale();
        original.call(pushed, x * scale, y, z * scale);
    }

    // Step raising the step height makes a stepped landing look like a big collision.
    // That would launch the player off slime or a bed. The y component is zeroed here.
    @ModifyArg(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;restituteMovementAfterCollisions(Lnet/minecraft/world/level/block/state/BlockState;ZZLnet/minecraft/world/phys/Vec3;)V"),
        index = 3)
    private Vec3 flattenSteppedLanding(Vec3 collided) {
        if ((Object) this != OfflineClient.MC.player || !verticalCollisionBelow || collided.y <= 0) {
            return collided;
        }
        Step step = Modules.get(Step.class);
        if (step == null || !step.raisesStepHeight()) {
            return collided;
        }
        return collided.with(Direction.Axis.Y, 0);
    }
}
