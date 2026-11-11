package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.AutoWasp;
import com.jellypudding.offlineclient.modules.movement.ElytraFly;
import com.jellypudding.offlineclient.modules.movement.NoSlowdown;
import com.jellypudding.offlineclient.modules.movement.Slippy;
import com.jellypudding.offlineclient.modules.movement.Speed;
import com.jellypudding.offlineclient.modules.movement.Sprint;
import com.jellypudding.offlineclient.modules.player.Ghost;
import com.jellypudding.offlineclient.modules.render.ClearView;
import com.jellypudding.offlineclient.modules.render.HandView;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.WholeMove;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    // The hand a hit swings. Only your own swings are changed.
    @ModifyVariable(method = "swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z",
        at = @At("HEAD"), argsOnly = true)
    private InteractionHand onSwingHand(InteractionHand hand) {
        if ((Object) this != OfflineClient.MC.player) {
            return hand;
        }
        HandView handView = Modules.get(HandView.class);
        return handView == null ? hand : handView.swingHand(hand);
    }

    // A dead body stands still. Ghost lets yours walk.
    @Inject(method = "isImmobile()Z", at = @At("HEAD"), cancellable = true)
    private void onIsImmobile(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this != OfflineClient.MC.player) {
            return;
        }
        Ghost ghost = Modules.get(Ghost.class);
        if (ghost != null && ghost.walking()) {
            cir.setReturnValue(false);
        }
    }

    // NoSlowdown lets you pass through vines without climbing them.
    @Inject(method = "onClimbable()Z", at = @At("HEAD"), cancellable = true)
    private void onClimbable(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this != OfflineClient.MC.player) {
            return;
        }
        NoSlowdown noSlowdown = Modules.get(NoSlowdown.class);
        if (noSlowdown != null && noSlowdown.skipsVine(((LivingEntity) (Object) this).getInBlockState())) {
            cir.setReturnValue(false);
        }
    }

    // Crumbs fly for food only. A drunk potion or a thrown egg keeps its particles.
    @Inject(method = "spawnItemParticles(Lnet/minecraft/world/item/ItemStack;I)V",
        at = @At("HEAD"), cancellable = true)
    private void onSpawnItemParticles(ItemStack stack, int count, CallbackInfo ci) {
        ClearView clearView = Modules.active(ClearView.class);
        if (clearView != null && clearView.blocksEatingCrumbs()
            && stack.getComponents().has(DataComponents.FOOD)) {
            ci.cancel();
        }
    }

    // The friction of the block underfoot sets the top speed. Slime is stickier than stone.
    @ModifyExpressionValue(method = "travelInAir(Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;getFriction()F"))
    private float onGroundFriction(float original) {
        if ((Object) this != OfflineClient.MC.player) {
            return original;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        Block below = self.level().getBlockState(self.getBlockPosBelowThatAffectsMyMovement()).getBlock();
        float friction = original;
        NoSlowdown noSlowdown = Modules.get(NoSlowdown.class);
        if (noSlowdown != null) {
            friction = noSlowdown.groundFriction(below, friction);
        }
        Slippy slippy = Modules.get(Slippy.class);
        return slippy == null ? friction : slippy.groundFriction(below, friction);
    }

    // On land and in the air the keys push the speed set before the move. A module that sets the
    // whole move of the tick leaves the push out. Speed holds a hop whose whole move passes its cap.
    @WrapOperation(method = "handleRelativeFrictionAndCalculateMovement(Lnet/minecraft/world/phys/Vec3;F)"
        + "Lnet/minecraft/world/phys/Vec3;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;moveRelative(FLnet/minecraft/world/phys/Vec3;)V"))
    private void onKeyPush(LivingEntity self, float push, Vec3 input, Operation<Void> original) {
        if (self != OfflineClient.MC.player) {
            original.call(self, push, input);
            return;
        }
        if (WholeMove.holds(self)) {
            original.call(self, 0f, input);
            return;
        }
        Vec3 unpushed = self.getDeltaMovement();
        original.call(self, push, input);
        Speed speed = Modules.active(Speed.class);
        if (speed != null) {
            speed.holdHop(unpushed);
        }
    }

    // Water lets through the share of the land speed this attribute names and half of that
    // off the bottom. NoSlowdown lets all of it through wherever you swim.
    @ModifyExpressionValue(method = "travelInWater(Lnet/minecraft/world/phys/Vec3;DZD)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getAttributeValue(Lnet/minecraft/core/Holder;)D"))
    private double onWaterEfficiency(double original) {
        return offlineclient$skipsWaterDrag() ? 1 : original;
    }

    @ModifyExpressionValue(method = "travelInWater(Lnet/minecraft/world/phys/Vec3;DZD)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;onGround()Z"))
    private boolean onWaterGrounded(boolean original) {
        return original || offlineclient$skipsWaterDrag();
    }

    // Lava pushes a swimmer with a fifth of the speed they have on land.
    @ModifyArg(method = "travelInLava(Lnet/minecraft/world/phys/Vec3;DZD)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;moveRelative(FLnet/minecraft/world/phys/Vec3;)V"),
        index = 0)
    private float onLavaPush(float original) {
        if ((Object) this != OfflineClient.MC.player) {
            return original;
        }
        NoSlowdown noSlowdown = Modules.get(NoSlowdown.class);
        return noSlowdown == null ? original
            : noSlowdown.lavaPush(original, ((LivingEntity) (Object) this).getSpeed());
    }

    // Dolphin's Grace puts a drag of its own in place of these sums. With the push of a walk
    // on land on top it would carry you away. The game's own sums stand whilst it is on.
    @Unique
    private boolean offlineclient$skipsWaterDrag() {
        if ((Object) this != OfflineClient.MC.player) {
            return false;
        }
        NoSlowdown noSlowdown = Modules.get(NoSlowdown.class);
        return noSlowdown != null && noSlowdown.skipsFluidDrag()
            && !((LivingEntity) (Object) this).hasEffect(MobEffects.DOLPHINS_GRACE);
    }

    // Packet ElytraFly holds a glide open on the server whilst you fly like creative.
    // The glide physics must not run against that flight.
    @ModifyExpressionValue(method = "travel(Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;isFallFlying()Z"))
    private boolean onTravelGliding(boolean original) {
        if (!original || (Object) this != OfflineClient.MC.player) {
            return original;
        }
        ElytraFly elytraFly = Modules.get(ElytraFly.class);
        return elytraFly == null || !elytraFly.flyingInGlide();
    }

    // AutoWasp steers the glide straight at its target instead of along the look.
    @ModifyExpressionValue(method = "travelFallFlying(Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;updateFallFlyingMovement(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 onGlideVelocity(Vec3 original) {
        if ((Object) this != OfflineClient.MC.player) {
            return original;
        }
        AutoWasp wasp = Modules.active(AutoWasp.class);
        if (wasp == null) {
            return original;
        }
        Vec3 steered = wasp.glideVelocity();
        return steered == null ? original : steered;
    }

    // The sprint jump pushes along the camera yaw. Rage sprint turns it along the keys.
    @ModifyExpressionValue(method = "jumpFromGround()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getYRot()F"))
    private float onJumpYaw(float original) {
        if ((Object) this != OfflineClient.MC.player) {
            return original;
        }
        Sprint sprint = Modules.get(Sprint.class);
        return sprint == null ? original : sprint.jumpYaw(original);
    }

    @ModifyExpressionValue(method = "jumpFromGround()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;isSprinting()Z"))
    private boolean onJumpSprinting(boolean original) {
        if ((Object) this != OfflineClient.MC.player) {
            return original;
        }
        Sprint sprint = Modules.get(Sprint.class);
        return sprint == null ? original : sprint.jumpBoost(original, (LocalPlayer) (Object) this);
    }

    @ModifyExpressionValue(method = "getModifiedSwingDuration(Lnet/minecraft/world/item/component/SwingAnimation;)I",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/component/SwingAnimation;duration()I"))
    private int onSwingDuration(int original) {
        if ((Object) this != OfflineClient.MC.player) {
            return original;
        }
        HandView handView = Modules.get(HandView.class);
        return handView == null ? original : handView.swingDuration(original);
    }
}
