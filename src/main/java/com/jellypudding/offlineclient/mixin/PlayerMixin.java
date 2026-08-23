package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.movement.EdgeGuard;
import com.jellypudding.offlineclient.modules.world.Scaffold;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {

    /**
     * Vanilla sneaking clamps the player to the edge. Scaffold has already
     * placed the block below whilst it is building downward.
     */
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

    /**
     * The sneak edge check probes down by the step height. EdgeGuard holds the
     * probe at the vanilla depth.
     */
    @WrapOperation(
        method = "maybeBackOffFromEdge(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/MoverType;)Lnet/minecraft/world/phys/Vec3;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;maxUpStep()F"))
    private float wrapEdgeProbeDepth(Player player, Operation<Float> original) {
        float depth = original.call(player);
        if (Modules.enabled(EdgeGuard.class)) {
            return Math.min(depth, 0.6f);
        }
        return depth;
    }
}
