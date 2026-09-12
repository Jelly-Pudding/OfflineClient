package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.AntiPush;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityFluidInteraction.class)
public abstract class EntityFluidInteractionMixin {

    // Every current a fluid pulls with is summed from this flow. AntiPush keeps a share of it.
    @ModifyExpressionValue(method = "update(Lnet/minecraft/world/entity/Entity;Z)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FluidState;getFlow(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 onFluidFlow(Vec3 flow, Entity entity, boolean ignoreCurrent) {
        if (entity != OfflineClient.MC.player) {
            return flow;
        }
        AntiPush antiPush = Modules.get(AntiPush.class);
        return antiPush == null ? flow : antiPush.scaleCurrent(flow);
    }
}
