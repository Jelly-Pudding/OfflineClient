package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.NoSlowdown;
import com.jellypudding.offlineclient.modules.render.HandView;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

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

    // The friction of the block underfoot sets the top speed. Slime is stickier than stone.
    @ModifyExpressionValue(method = "travelInAir(Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;getFriction()F"))
    private float onGroundFriction(float original) {
        if ((Object) this != OfflineClient.MC.player) {
            return original;
        }
        NoSlowdown noSlowdown = Modules.get(NoSlowdown.class);
        if (noSlowdown == null) {
            return original;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        Block below = self.level().getBlockState(self.getBlockPosBelowThatAffectsMyMovement()).getBlock();
        return noSlowdown.groundFriction(below, original);
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
