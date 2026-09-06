package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.AntiPush;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.PotentSulfurBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// A geyser launches whatever stands in its column via a block entity
// ticker. It is the fifth static lambda and the only push the server never sends.
@Mixin(PotentSulfurBlockEntity.class)
public abstract class PotentSulfurBlockEntityMixin {

    @WrapOperation(method = "lambda$static$5",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;addDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private static void wrapGeyserLaunch(Entity entity, Vec3 push, Operation<Void> original) {
        AntiPush antiPush = Modules.get(AntiPush.class);
        if (entity == OfflineClient.MC.player && antiPush != null && antiPush.blocksGeysers()) {
            return;
        }
        original.call(entity, push);
    }
}
