package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.movement.TridentBoost;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.TridentItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TridentItem.class)
public abstract class TridentItemMixin {

    // The riptide launch is one push on the player.
    @WrapOperation(method = "releaseUsing",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;push(DDD)V"))
    private void wrapRiptidePush(Player player, double x, double y, double z,
                                 Operation<Void> original) {
        TridentBoost boost = Modules.get(TridentBoost.class);
        double scale = boost == null ? 1 : boost.multiplier();
        original.call(player, x * scale, y * scale, z * scale);
    }

    // Both the draw and the release refuse a riptide on dry land.
    @WrapOperation(method = {"use", "releaseUsing"},
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isInWaterOrRain()Z"))
    private boolean wrapWetCheck(Player player, Operation<Boolean> original) {
        TridentBoost boost = Modules.get(TridentBoost.class);
        return (boost != null && boost.allowsDryLand()) || original.call(player);
    }
}
