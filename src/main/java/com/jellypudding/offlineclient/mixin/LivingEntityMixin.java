package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.AutoWasp;
import com.jellypudding.offlineclient.modules.movement.NoSlowdown;
import com.jellypudding.offlineclient.modules.movement.Slippy;
import com.jellypudding.offlineclient.modules.movement.Sprint;
import com.jellypudding.offlineclient.modules.render.ClearView;
import com.jellypudding.offlineclient.modules.render.HandView;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    // The hand a hit swings. Only your own swings are changed.
    @ModifyVariable(method = "swing(Lnet/minecraft/world/InteractionHand;)V",
        at = @At("HEAD"), argsOnly = true)
    private InteractionHand onSwingHand(InteractionHand hand) {
        if ((Object) this != OfflineClient.MC.player) {
            return hand;
        }
        HandView handView = Modules.get(HandView.class);
        return handView == null ? hand : handView.swingHand(hand);
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

    @ModifyExpressionValue(method = "getCurrentSwingDuration()I",
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
