package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.NoClip;
import com.jellypudding.offlineclient.modules.movement.Sprint;
import com.jellypudding.offlineclient.modules.movement.Step;
import com.jellypudding.offlineclient.modules.world.Scaffold;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.SprintPause;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {

    // Vanilla sneaking clamps the player to the edge. Scaffold has already
    // placed the block below whilst it is building downward.
    @Inject(
        method = "maybeBackOffFromEdge(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/MoverType;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("HEAD"),
        cancellable = true)
    private void onMaybeBackOffFromEdge(Vec3 movement, MoverType type,
                                        CallbackInfoReturnable<Vec3> cir) {
        Scaffold scaffold = Modules.get(Scaffold.class);
        if (scaffold != null && scaffold.isDescending()) {
            cir.setReturnValue(movement);
        }
    }

    // The sneak edge check probes down by the step height. Whatever Step raises that to
    // the probe keeps the depth of the plain game unless Step asks for a deeper one.
    @WrapOperation(
        method = "maybeBackOffFromEdge(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/MoverType;)Lnet/minecraft/world/phys/Vec3;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;maxUpStep()F"))
    private float wrapEdgeProbeDepth(Player player, Operation<Float> original) {
        if (player != OfflineClient.MC.player) {
            return original.call(player);
        }
        float plain = (float) player.getAttributeValue(Attributes.STEP_HEIGHT);
        Step step = Modules.get(Step.class);
        return step == null ? plain : step.edgeProbe(plain);
    }

    // The client plays its own hit out the way the server is going to see it. A hit the
    // server sees no sprint for knocks no further back and costs neither speed nor sprint.
    @ModifyExpressionValue(
        method = {"attack(Lnet/minecraft/world/entity/Entity;)V",
            "canCriticalAttack(Lnet/minecraft/world/entity/Entity;)Z"},
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isSprinting()Z"))
    private boolean onHitSprinting(boolean original) {
        return original && !((Object) this == OfflineClient.MC.player && SprintPause.hitUnsprinted());
    }

    // A hit that knocks back takes two fifths of the speed and clears the sprint. Sprint
    // can keep both.
    @WrapOperation(
        method = "causeExtraKnockback(Lnet/minecraft/world/entity/Entity;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/damagesource/DamageSource;FZ)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void wrapHitSlowdown(Player player, Vec3 velocity, Operation<Void> original) {
        if (!offlineclient$keepsSprint(player)) {
            original.call(player, velocity);
        }
    }

    @WrapOperation(
        method = "causeExtraKnockback(Lnet/minecraft/world/entity/Entity;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/damagesource/DamageSource;FZ)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;setSprinting(Z)V"))
    private void wrapHitSprintStop(Player player, boolean sprinting, Operation<Void> original) {
        if (!offlineclient$keepsSprint(player)) {
            original.call(player, sprinting);
        }
    }

    @Unique
    private static boolean offlineclient$keepsSprint(Player player) {
        Sprint sprint = Modules.get(Sprint.class);
        return player == OfflineClient.MC.player && sprint != null && sprint.keepsSprintOnHit();
    }

    // The physics flag is rebuilt from this every tick. NoClip answers yes.
    @WrapOperation(method = "tick()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isSpectator()Z", ordinal = 0))
    private boolean wrapNoPhysics(Player player, Operation<Boolean> original) {
        if (original.call(player)) {
            return true;
        }
        NoClip noClip = Modules.get(NoClip.class);
        return player == OfflineClient.MC.player && noClip != null && noClip.passesThroughBlocks();
    }
}
